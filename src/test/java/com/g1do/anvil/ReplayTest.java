package com.g1do.anvil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.replay.ReplayService;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic replay endpoint proofs (issue #30).
 *
 * <p>Proves on containerized Postgres: happy-path match:true with
 * input_sha/output_sha roots, repeat identical with no log/job writes, corrupt /
 * drop / swap mismatch with offending seq, unlogged heartbeat identical output,
 * checkpoints every 100 with checkpoint replay equal to full replay across two
 * boundaries, append-only enforcement, and cross-tenant fail-closed deny.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReplayTest {

    private static final String TENANT_A = "t_replay_a";
    private static final String TENANT_B = "t_replay_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submitService;

    @Autowired
    JobService jobs;

    @Autowired
    ReplayService replays;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PlatformTransactionManager txManager;

    @TempDir
    java.nio.file.Path tempDir;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE job_checkpoints, audit_events");
        jdbc.execute("DELETE FROM cap_grants");
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM attempts");
        jdbc.execute("DELETE FROM jobs");
        jdbc.execute("DELETE FROM document_versions");
        jdbc.execute("DELETE FROM documents");
        jdbc.execute("DELETE FROM artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id NOT IN ('t_single', '" + TENANT_A + "', '" + TENANT_B + "')");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_A);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_B);
    }

    private SubmitService.SubmitResult submitTerminal(String tenant, String keySuffix) throws Exception {
        var submitted = submitService.submit(tenant, "replay-" + keySuffix + "-" + UUID.randomUUID(),
                "Replay Doc", "text/plain", "replay-body-" + keySuffix);
        UUID job = submitted.jobId();
        ClaimedJob claimed = jobs.claim(tenant, "replay-w-" + keySuffix).orElseThrow();
        // Ensure we claimed our own job (single-claimant per tenant in test).
        if (!claimed.jobId().equals(job)) {
            // Drain other queued jobs from earlier tests in same tenant (should not happen
            // with clean(), but guard for pooled reuse).
            throw new IllegalStateException("claimed wrong job");
        }
        CapGrant cap = jobs.issueCap(tenant, job);
        ContentStore store = new ContentStore(jdbc, txManager, tempDir.toString());
        byte[] bytes = ("replay-bytes-" + keySuffix).getBytes(StandardCharsets.UTF_8);
        store.putForJob(tenant, job, bytes);
        boolean ok = jobs.commitSucceeded(tenant, job, "replay-w-" + keySuffix,
                claimed.fencingToken(), cap.capId(), cap.epoch());
        assertThat(ok).isTrue();
        return submitted;
    }

    private String versionSha(String tenant, UUID versionId) {
        return jdbc.queryForObject(
                "SELECT sha256 FROM document_versions WHERE tenant_id = ? AND id = ?",
                String.class, tenant, versionId);
    }

    private String expectedOutputSha(UUID jobId, String terminalState, String artifactSha) {
        return ReplayService.outputSha(jobId, terminalState, artifactSha);
    }

    private String lastCasArtifact(String tenant, UUID jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, payload FROM audit_events WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                tenant, jobId);
        String last = "";
        for (Map<String, Object> r : rows) {
            if ("cas.put".equals(r.get("type"))) {
                Object raw = r.get("payload");
                try {
                    String js = raw instanceof org.postgresql.util.PGobject pg
                            ? pg.getValue() : raw.toString();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = jsonMapper.readValue(js, Map.class);
                    Object v = payload.get("artifact_sha");
                    if (v instanceof String s && s.matches("^[0-9a-f]{64}$")) {
                        last = s;
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return last;
    }

    private int count(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    @Test
    void happyPathMatchTrueWithRoots() throws Exception {
        var submitted = submitTerminal(TENANT_A, "happy");
        UUID job = submitted.jobId();

        ReplayService.ReplayResult r = replays.replay(TENANT_A, job);
        assertThat(r.match()).isTrue();
        assertThat(r.mismatchSeq()).isNull();

        String dbInputSha = versionSha(TENANT_A, submitted.versionId());
        assertThat(r.inputSha()).isEqualTo(dbInputSha);

        String artifact = lastCasArtifact(TENANT_A, job);
        assertThat(artifact).matches("^[0-9a-f]{64}$");
        String terminal = jdbc.queryForObject(
                "SELECT state FROM jobs WHERE tenant_id = ? AND id = ?", String.class, TENANT_A, job);
        String expectedOutput = expectedOutputSha(job, terminal, artifact);
        assertThat(r.outputSha()).isEqualTo(expectedOutput);

        // Full path agrees.
        ReplayService.ReplayResult full = replays.replayFull(TENANT_A, job);
        assertThat(full.match()).isTrue();
        assertThat(full.inputSha()).isEqualTo(r.inputSha());
        assertThat(full.outputSha()).isEqualTo(r.outputSha());
    }

    @Test
    void intactRepeatIdenticalNoWrites() throws Exception {
        var submitted = submitTerminal(TENANT_A, "idem");
        UUID job = submitted.jobId();

        int auditBefore = count("SELECT COUNT(*) FROM audit_events");
        int ckptBefore = count("SELECT COUNT(*) FROM job_checkpoints");
        int jobsBefore = count("SELECT COUNT(*) FROM jobs");
        int attemptsBefore = count("SELECT COUNT(*) FROM attempts");
        int artifactsBefore = count("SELECT COUNT(*) FROM artifacts");

        ReplayService.ReplayResult first = replays.replay(TENANT_A, job);
        ReplayService.ReplayResult second = replays.replay(TENANT_A, job);

        assertThat(second.inputSha()).isEqualTo(first.inputSha());
        assertThat(second.outputSha()).isEqualTo(first.outputSha());
        assertThat(second.match()).isTrue();

        assertThat(count("SELECT COUNT(*) FROM audit_events")).isEqualTo(auditBefore);
        assertThat(count("SELECT COUNT(*) FROM job_checkpoints")).isEqualTo(ckptBefore);
        assertThat(count("SELECT COUNT(*) FROM jobs")).isEqualTo(jobsBefore);
        assertThat(count("SELECT COUNT(*) FROM attempts")).isEqualTo(attemptsBefore);
        assertThat(count("SELECT COUNT(*) FROM artifacts")).isEqualTo(artifactsBefore);
    }

    @Test
    void corruptOneByteYieldsMismatch() {
        var submitted = submitService.submit(TENANT_A, "replay-corrupt-" + UUID.randomUUID(),
                "Corrupt", "text/plain", "corrupt-body");
        UUID job = submitted.jobId();
        String x0 = jdbc.queryForObject(
                "SELECT event_hash FROM audit_events WHERE job_id = ? ORDER BY seq LIMIT 1",
                String.class, job);

        // Correct hash for original payload, but store tampered payload: 1-byte corrupt.
        Map<String, Object> original = Map.of("state", "RUNNING");
        Map<String, Object> tampered = Map.of("state", "RUNNINH");
        byte[] canonicalOriginal = CanonicalCbor.encodeMap(original);
        String wrongHash = AuditChain.computeHash("claim", job, 1L, x0, canonicalOriginal);
        String tamperedJson;
        try {
            tamperedJson = jsonMapper.writeValueAsString(tampered);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, 1, ?, ?, 'claim', ?::jsonb)",
                TENANT_A, job, x0, wrongHash, tamperedJson);

        ReplayService.ReplayResult r = replays.replay(TENANT_A, job);
        assertThat(r.match()).isFalse();
        assertThat(r.mismatchSeq()).isEqualTo(1L);
    }

    @Test
    void dropKeptEventYieldsMismatch() {
        var submitted = submitService.submit(TENANT_A, "replay-drop-" + UUID.randomUUID(),
                "Drop", "text/plain", "drop-body");
        UUID job = submitted.jobId();
        String x0 = jdbc.queryForObject(
                "SELECT event_hash FROM audit_events WHERE job_id = ? ORDER BY seq LIMIT 1",
                String.class, job);

        // seq1 intact.
        Map<String, Object> p1 = Map.of("state", "RUNNING");
        String h1 = AuditChain.computeHash("claim", job, 1L, x0, CanonicalCbor.encodeMap(p1));
        String p1Json;
        try {
            p1Json = jsonMapper.writeValueAsString(p1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, 1, ?, ?, 'claim', ?::jsonb)",
                TENANT_A, job, x0, h1, p1Json);

        // Drop seq2: insert seq3 with prev pointing at seq1.
        Map<String, Object> p3 = Map.of("outcome", "SUCCEEDED");
        String h3 = AuditChain.computeHash("commit", job, 3L, h1, CanonicalCbor.encodeMap(p3));
        String p3Json;
        try {
            p3Json = jsonMapper.writeValueAsString(p3);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, 3, ?, ?, 'commit', ?::jsonb)",
                TENANT_A, job, h1, h3, p3Json);

        ReplayService.ReplayResult r = replays.replay(TENANT_A, job);
        assertThat(r.match()).isFalse();
        assertThat(r.mismatchSeq()).isEqualTo(3L);
    }

    @Test
    void swapPrevHashYieldsMismatch() {
        var submitted = submitService.submit(TENANT_A, "replay-swap-" + UUID.randomUUID(),
                "Swap", "text/plain", "swap-body");
        UUID job = submitted.jobId();

        String wrongPrev = "a".repeat(64);
        Map<String, Object> p1 = Map.of("state", "RUNNING");
        String h1 = AuditChain.computeHash("claim", job, 1L, wrongPrev, CanonicalCbor.encodeMap(p1));
        String p1Json;
        try {
            p1Json = jsonMapper.writeValueAsString(p1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, 1, ?, ?, 'claim', ?::jsonb)",
                TENANT_A, job, wrongPrev, h1, p1Json);

        ReplayService.ReplayResult r = replays.replay(TENANT_A, job);
        assertThat(r.match()).isFalse();
        assertThat(r.mismatchSeq()).isEqualTo(1L);
    }

    @Test
    void dropUnloggedHeartbeatSameOutput() {
        var submitted = submitService.submit(TENANT_A, "replay-hb-" + UUID.randomUUID(),
                "Hb", "text/plain", "hb-body");
        UUID job = submitted.jobId();
        ClaimedJob claimed = jobs.claim(TENANT_A, "replay-hb-w").orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(job);

        ReplayService.ReplayResult before = replays.replay(TENANT_A, job);
        assertThat(before.match()).isFalse(); // RUNNING is non-terminal.

        assertThat(jobs.heartbeat(TENANT_A, job, "replay-hb-w", claimed.fencingToken())).isTrue();

        ReplayService.ReplayResult after = replays.replay(TENANT_A, job);
        assertThat(after.outputSha()).isEqualTo(before.outputSha());
        assertThat(after.inputSha()).isEqualTo(before.inputSha());
        assertThat(after.match()).isFalse();
    }

    @Test
    void checkpointsEvery100AndReplayEqualsFull() {
        var submitted = submitService.submit(TENANT_A, "replay-long-" + UUID.randomUUID(),
                "Long", "text/plain", "long-body");
        UUID job = submitted.jobId();

        TransactionTemplate txn = new TransactionTemplate(txManager);
        // 250 appends -> total 251 events (seq 0..250), checkpoints at 100 and 200.
        for (int i = 1; i <= 250; i++) {
            final int n = i;
            txn.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, TENANT_A);
                AuditChain.append(jdbc, TENANT_A, job, AuditChain.TYPE_CLAIM,
                        Map.of("state", "RUNNING", "k", "v" + n));
                return null;
            });
        }

        List<Map<String, Object>> cps = jdbc.queryForList(
                "SELECT seq, sha FROM job_checkpoints WHERE job_id = ? ORDER BY seq", job);
        assertThat(cps.stream().map(m -> ((Number) m.get("seq")).longValue()))
                .contains(100L, 200L);
        assertThat(cps).hasSize(2);

        // Chain length crosses two boundaries.
        Integer chainSize = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE job_id = ?", Integer.class, job);
        assertThat(chainSize).isEqualTo(251);

        ReplayService.ReplayResult viaCheckpoint = replays.replay(TENANT_A, job);
        ReplayService.ReplayResult viaFull = replays.replayFull(TENANT_A, job);

        assertThat(viaCheckpoint.outputSha()).isEqualTo(viaFull.outputSha());
        assertThat(viaCheckpoint.inputSha()).isEqualTo(viaFull.inputSha());
        // Proof the checkpoint path was taken (crosses >=2 boundaries).
        assertThat(viaCheckpoint.checkpointSeqUsed()).isNotNull();
        assertThat(viaCheckpoint.checkpointSeqUsed()).isGreaterThanOrEqualTo(200L);
        // Non-terminal long chain still reports match:false (RUNNING never committed),
        // but both paths agree.
        assertThat(viaCheckpoint.match()).isEqualTo(viaFull.match());
    }

    @Test
    void checkpointsAppendOnlyAndMigrationShape() {
        var submitted = submitService.submit(TENANT_A, "replay-shape-" + UUID.randomUUID(),
                "Shape", "text/plain", "shape-body");
        UUID job = submitted.jobId();

        TransactionTemplate txn = new TransactionTemplate(txManager);
        for (int i = 1; i <= 100; i++) {
            final int n = i;
            txn.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, TENANT_A);
                AuditChain.append(jdbc, TENANT_A, job, AuditChain.TYPE_CLAIM,
                        Map.of("state", "RUNNING", "k", "s" + n));
                return null;
            });
        }
        // One checkpoint at seq 100.
        assertThat(count("SELECT COUNT(*) FROM job_checkpoints WHERE job_id = ?", job)).isEqualTo(1);

        // Migration shape.
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = 'job_checkpoints'",
                String.class);
        assertThat(tables).containsExactly("job_checkpoints");
        List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'job_checkpoints'",
                String.class);
        assertThat(cols).contains("tenant_id", "job_id", "seq", "sha");
        Integer uniq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 'job_checkpoints' AND indexdef LIKE '%(job_id, seq)%'",
                Integer.class);
        assertThat(uniq).isGreaterThanOrEqualTo(1);
        Map<String, Object> rls = jdbc.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'job_checkpoints'");
        assertThat(rls.get("relrowsecurity")).isEqualTo(true);
        assertThat(rls.get("relforcerowsecurity")).isEqualTo(true);
        String owner = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'job_checkpoints'",
                String.class);
        assertThat(owner).isEqualTo("anvil_migrator");
        Boolean hasUpdate = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'job_checkpoints', 'UPDATE')", Boolean.class);
        assertThat(hasUpdate).isFalse();
        Boolean hasDelete = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'job_checkpoints', 'DELETE')", Boolean.class);
        assertThat(hasDelete).isFalse();
        Boolean hasInsert = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'job_checkpoints', 'INSERT')", Boolean.class);
        assertThat(hasInsert).isTrue();
        Integer trg = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'job_checkpoints_immutable'", Integer.class);
        assertThat(trg).isGreaterThanOrEqualTo(1);

        // Append-only: UPDATE/DELETE rejected even as superuser.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE job_checkpoints SET sha = ? WHERE job_id = ?", "f".repeat(64), job))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM job_checkpoints WHERE job_id = ?", job))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // And audit_events stays append-only.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET type = 'claim' WHERE job_id = ?", job))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void crossTenantDenyFailClosed() {
        var submittedA = submitService.submit(TENANT_A, "replay-xa-" + UUID.randomUUID(),
                "XA", "text/plain", "xa-body");
        UUID jobA = submittedA.jobId();
        var submittedB = submitService.submit(TENANT_B, "replay-xb-" + UUID.randomUUID(),
                "XB", "text/plain", "xb-body");
        UUID jobB = submittedB.jobId();

        // Service: cross-tenant throws exactly like missing (no oracle).
        assertThatThrownBy(() -> replays.replay(TENANT_A, jobB))
                .isInstanceOf(ReplayService.ReplayNotFoundException.class);
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> replays.replay(TENANT_A, missing))
                .isInstanceOf(ReplayService.ReplayNotFoundException.class);
        // Control: owner succeeds.
        assertThat(replays.replay(TENANT_A, jobA)).isNotNull();
        assertThat(replays.replay(TENANT_B, jobB)).isNotNull();
    }

    @Test
    void httpMatrix() throws Exception {
        var submitted = submitTerminal(TENANT_A, "http");
        UUID job = submitted.jobId();
        var submittedB = submitService.submit(TENANT_B, "replay-httpb-" + UUID.randomUUID(),
                "HB", "text/plain", "httpb-body");
        UUID jobB = submittedB.jobId();

        // Own-tenant 200 with shas.
        var own = mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", job.toString()))
                .andExpect(status().isOk())
                .andReturn();
        var ownTree = jsonMapper.readTree(own.getResponse().getContentAsString());
        assertThat(ownTree.get("input_sha").asText()).matches("^[0-9a-f]{64}$");
        assertThat(ownTree.get("output_sha").asText()).matches("^[0-9a-f]{64}$");
        assertThat(ownTree.get("match").asBoolean()).isTrue();

        // Alias route works.
        mockMvc.perform(get("/api/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", job.toString()))
                .andExpect(status().isOk());

        // limit/cursor aliases ignored (still 200).
        mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", job.toString())
                        .param("limit", "10"))
                .andExpect(status().isOk());

        // Forged tenant param ignored (header authority).
        var forged = mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", job.toString())
                        .param("tenant", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jsonMapper.readTree(forged.getResponse().getContentAsString())
                .get("match").asBoolean()).isTrue();

        // Cross-tenant 404 with no bytes.
        var cross = mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobB.toString()))
                .andExpect(status().isNotFound())
                .andReturn();
        String crossBody = cross.getResponse().getContentAsString();
        assertThat(crossBody).doesNotContain("input_sha");
        assertThat(crossBody).doesNotContain("output_sha");

        // Missing 404 same shape (no oracle: same status, no bytes).
        var missing = mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(missing.getResponse().getContentAsString()).doesNotContain("input_sha");

        // Non-terminal own-tenant returns match:false (not 404).
        var running = submitService.submit(TENANT_A, "replay-httprun-" + UUID.randomUUID(),
                "RUN", "text/plain", "run-body");
        jobs.claim(TENANT_A, "replay-http-w").orElseThrow();
        var nonterm = mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", running.jobId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(jsonMapper.readTree(nonterm.getResponse().getContentAsString())
                .get("match").asBoolean()).isFalse();

        // 401 / 400.
        mockMvc.perform(get("/audit/replay")
                        .param("job_id", job.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit/replay")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", job.toString())
                        .param("cursor", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
