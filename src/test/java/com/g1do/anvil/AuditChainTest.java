package com.g1do.anvil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.submit.SubmitService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Immutable per-job hash-chained audit log proofs (issue #28).
 *
 * <p>Proves on containerized Postgres: migration shape, hash linkage with the
 * existing canonical mapper, same-transaction append, duplicate-resubmit no
 * fork, non-logged steps, immutability, fork/FK rejection, crash atomicity and
 * fail-closed reads.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditChainTest {

    private static final String TENANT_A = "t_audit_a";
    private static final String TENANT_B = "t_audit_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    SubmitService submitService;

    @Autowired
    JobService jobs;

    @Autowired
    OutboxRelay relay;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @TempDir
    java.nio.file.Path tempDir;

    private HikariDataSource appPool;
    private JdbcTemplate appJdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE audit_events");
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

    @AfterEach
    void closePool() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
    }

    private HikariDataSource newAppPool() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(hikari.getJdbcUrl());
        config.setUsername("anvil_app");
        config.setPassword("anvil_app");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setPoolName("audit-app-pool-" + UUID.randomUUID());
        return new HikariDataSource(config);
    }

    private List<Map<String, Object>> chain(String tenant, UUID jobId) {
        return jdbc.queryForList(
                "SELECT tenant_id, job_id, seq, prev_hash, event_hash, type, payload "
                        + "FROM audit_events WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                tenant, jobId);
    }

    private void assertChainLinked(String tenant, UUID jobId) {
        List<Map<String, Object>> rows = chain(tenant, jobId);
        assertThat(rows).isNotEmpty();
        String prev = AuditChain.GENESIS;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long seq = ((Number) row.get("seq")).longValue();
            assertThat(seq).isEqualTo((long) i);
            assertThat((String) row.get("prev_hash")).isEqualTo(prev);
            String type = (String) row.get("type");
            String eventHash = (String) row.get("event_hash");
            assertThat(eventHash).matches("^[0-9a-f]{64}$");
            // Recompute from stored payload via the canonical mapper.
            Map<String, Object> payload;
            try {
                Object raw = row.get("payload");
                String payloadJson = raw instanceof org.postgresql.util.PGobject pg
                        ? pg.getValue() : raw.toString();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(payloadJson, Map.class);
                payload = parsed;
            } catch (Exception e) {
                throw new IllegalStateException("payload decode failed", e);
            }
            byte[] canonical = CanonicalCbor.encodeMap(payload);
            String recomputed = AuditChain.computeHash(type, jobId, seq, prev, canonical);
            assertThat(eventHash).as("event_hash recomputed for seq %d type %s", seq, type)
                    .isEqualTo(recomputed);
            // Excluded fields never in payload.
            for (String forbidden : List.of("owner", "fencing_token", "lease_expires_at", "attempt",
                    "attempt_number", "created_at", "updated_at", "sent_at", "tenant_id", "tenant",
                    "app.tenant")) {
                assertThat(payload).doesNotContainKey(forbidden);
            }
            prev = eventHash;
        }
    }

    @Test
    void migrationShape() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = 'audit_events'",
                String.class);
        assertThat(tables).containsExactly("audit_events");

        // Required columns.
        List<String> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'audit_events'",
                String.class);
        assertThat(cols).contains("tenant_id", "job_id", "seq", "prev_hash", "event_hash", "type",
                "payload");

        // UNIQUE(job_id, prev_hash) exactly as specified (fork guard).
        Integer forkGuard = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 'audit_events' AND indexdef LIKE '%(job_id, prev_hash)%'",
                Integer.class);
        assertThat(forkGuard).isGreaterThanOrEqualTo(1);

        // Composite FK (tenant_id, job_id) -> jobs, no cross-tenant dedup.
        Integer fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_name = 'audit_events' "
                        + "AND constraint_type = 'FOREIGN KEY'",
                Integer.class);
        assertThat(fkCount).isGreaterThanOrEqualTo(1);

        // RLS FORCE + policy.
        Map<String, Object> rls = jdbc.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'audit_events'");
        assertThat(rls.get("relrowsecurity")).isEqualTo(true);
        assertThat(rls.get("relforcerowsecurity")).isEqualTo(true);
        String qual = jdbc.queryForObject(
                "SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = 'audit_events' "
                        + "AND policyname = 'tenant_isolation'",
                String.class);
        assertThat(qual).contains("current_setting('app.tenant'");
        String check = jdbc.queryForObject(
                "SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'audit_events' "
                        + "AND policyname = 'tenant_isolation'",
                String.class);
        assertThat(check).contains("current_setting('app.tenant'");

        // Owner + least privilege: migrator owns, app has SELECT+INSERT only.
        String owner = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'audit_events'",
                String.class);
        assertThat(owner).isEqualTo("anvil_migrator");
        Boolean hasUpdate = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'audit_events', 'UPDATE')", Boolean.class);
        assertThat(hasUpdate).isFalse();
        Boolean hasDelete = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'audit_events', 'DELETE')", Boolean.class);
        assertThat(hasDelete).isFalse();
        Boolean hasInsert = jdbc.queryForObject(
                "SELECT has_table_privilege('anvil_app', 'audit_events', 'INSERT')", Boolean.class);
        assertThat(hasInsert).isTrue();

        // Immutable trigger present.
        Integer trg = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'audit_events_immutable'", Integer.class);
        assertThat(trg).isGreaterThanOrEqualTo(1);
    }

    @Test
    void fullChainSubmitClaimCapCasCommitIsHashLinked() throws Exception {
        var submitted = submitService.submit("t_single", "audit-full-" + UUID.randomUUID(),
                "Audit Doc", "text/plain", "audit-body");
        UUID job = submitted.jobId();

        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(job);
        CapGrant cap = jobs.issueCap("t_single", job);

        ContentStore store = new ContentStore(jdbc, txManager, tempDir.toString());
        byte[] bytes = "audit-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = store.putForJob("t_single", job, bytes);

        assertThat(jobs.commitSucceeded("t_single", job, "w1", claimed.fencingToken(),
                cap.capId(), cap.epoch())).isTrue();

        List<Map<String, Object>> rows = chain("t_single", job);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).get("type")).isEqualTo("submit");
        assertThat(rows.get(1).get("type")).isEqualTo("claim");
        assertThat(rows.get(2).get("type")).isEqualTo("cap.issue");
        assertThat(rows.get(3).get("type")).isEqualTo("cas.put");
        assertThat(rows.get(4).get("type")).isEqualTo("commit");

        // Payload spot checks: submit links doc/version/sha, cap carries epoch,
        // cas links artifact_sha, commit carries outcome.
        assertThat(rows.get(2).get("payload").toString()).contains(String.valueOf(cap.epoch()));
        assertThat(rows.get(3).get("payload").toString()).contains(sha);
        assertThat(rows.get(4).get("payload").toString()).contains("SUCCEEDED");

        assertChainLinked("t_single", job);

        // Canonical mapper determinism: same logical payload in different key order
        // encodes identically (SORT_PROPERTIES_ALPHABETICALLY + ORDER_MAP_ENTRIES_BY_KEYS).
        byte[] a = CanonicalCbor.encodeMap(Map.of("b", "2", "a", "1"));
        java.util.LinkedHashMap<String, Object> reversed = new java.util.LinkedHashMap<>();
        reversed.put("b", "2");
        reversed.put("a", "1");
        byte[] b = CanonicalCbor.encodeMap(reversed);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void duplicateResubmitDoesNotForkChain() {
        String key = "audit-dupe-" + UUID.randomUUID();
        var first = submitService.submit("t_single", key, "Dupe", "text/plain", "dupe-body");
        var second = submitService.submit("t_single", key, "Dupe", "text/plain", "dupe-body");
        assertThat(second.jobId()).isEqualTo(first.jobId());
        List<Map<String, Object>> rows = chain("t_single", first.jobId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("submit");
        assertThat(rows.get(0).get("prev_hash")).isEqualTo(AuditChain.GENESIS);
        assertChainLinked("t_single", first.jobId());
    }

    @Test
    void heartbeatReconcilePollAndSentAtAreNotLogged() {
        var submitted = submitService.submit("t_single", "audit-quiet-" + UUID.randomUUID(),
                "Quiet", "text/plain", "quiet-body");
        UUID job = submitted.jobId();
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        int afterClaim = chain("t_single", job).size();

        // Heartbeat extends the lease but never appends.
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken())).isTrue();
        assertThat(chain("t_single", job)).hasSize(afterClaim);

        // Reconciler poll with a live lease reclaims nothing and appends nothing.
        jobs.reconcile("t_single");
        assertThat(chain("t_single", job)).hasSize(afterClaim);

        // Outbox relay marks sent_at in its own txn; no audit row follows.
        int marked = relay.relayOnce((event, headers) -> {
        }, "t_single");
        assertThat(marked).isGreaterThanOrEqualTo(1);
        assertThat(chain("t_single", job)).hasSize(afterClaim);
    }

    @Test
    void reconcilerDeadAndCapRevokeAreLogged() {
        // Exhausted QUEUED job -> DEAD via reconciler.
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                doc, "t_single", "dead-doc", "text/plain");
        UUID ver = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, "t_single", doc, "f".repeat(64));
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state, attempt, max_attempts) "
                        + "VALUES (?, ?, ?, ?, ?, 'QUEUED', 5, 5)",
                job, "t_single", doc, ver, "audit-dead-" + UUID.randomUUID());
        // Seed genesis submit audit so the DEAD event has a predecessor.
        TransactionTemplate txn = new TransactionTemplate(txManager);
        txn.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, "t_single");
            AuditChain.append(jdbc, "t_single", job, AuditChain.TYPE_SUBMIT,
                    Map.of("doc_id", doc.toString(), "version_id", ver.toString(), "sha256",
                            "f".repeat(64)));
            return null;
        });
        jobs.reconcile("t_single");
        List<Map<String, Object>> rows = chain("t_single", job);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("type")).isEqualTo("commit");
        assertThat(rows.get(1).get("payload").toString()).contains("DEAD");
        assertChainLinked("t_single", job);

        // Cap revoke carries epoch.
        var submitted = submitService.submit("t_single", "audit-revoke-" + UUID.randomUUID(),
                "Revoke", "text/plain", "revoke-body");
        UUID job2 = submitted.jobId();
        jobs.claim("t_single", "w1").orElseThrow();
        CapGrant cap = jobs.issueCap("t_single", job2);
        jobs.revokeCap("t_single", job2);
        List<Map<String, Object>> rows2 = chain("t_single", job2);
        assertThat(rows2.stream().map(r -> (String) r.get("type")))
                .containsExactly("submit", "claim", "cap.issue", "cap.revoke");
        assertThat(rows2.get(3).get("payload").toString()).contains(String.valueOf(cap.epoch()));
        assertChainLinked("t_single", job2);
    }

    @Test
    void updateDeleteRejectedDuplicatePrevHashRejectedCrossTenantFkRejected() {
        var submitted = submitService.submit("t_single", "audit-guard-" + UUID.randomUUID(),
                "Guard", "text/plain", "guard-body");
        UUID job = submitted.jobId();

        // Trigger rejects UPDATE/DELETE even as superuser.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET type = 'claim' WHERE job_id = ?", job))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE job_id = ?", job))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(chain("t_single", job)).hasSize(1);

        // Duplicate prev_hash for one job rejected (fork guard).
        Map<String, Object> tail = jdbc.queryForMap(
                "SELECT seq, prev_hash FROM audit_events WHERE job_id = ? ORDER BY seq DESC LIMIT 1", job);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, ?, ?, ?, 'claim', '{}'::jsonb)",
                "t_single", job, 999L, (String) tail.get("prev_hash"), "a".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // Cross-tenant FK rejected: tenant B cannot log for t_single's job.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, 999, 'GENESIS', ?, 'submit', '{}'::jsonb)",
                TENANT_A, job, "b".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // Runtime role cannot UPDATE/DELETE (grants + trigger).
        appPool = newAppPool();
        appJdbc = new JdbcTemplate(appPool);
        TransactionTemplate appTx = new TransactionTemplate(
                new DataSourceTransactionManager(appPool));
        assertThatThrownBy(() -> appTx.execute(status -> {
            appJdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, "t_single");
            return appJdbc.update("UPDATE audit_events SET type = 'claim' WHERE job_id = ?", job);
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> appTx.execute(status -> {
            appJdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, "t_single");
            return appJdbc.update("DELETE FROM audit_events WHERE job_id = ?", job);
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void crashBetweenEffectAndAppendLeavesNoOrphan() {
        // Simulated crash: effect + audit in one txn rolled back before commit.
        UUID doc = UUID.randomUUID();
        UUID ver = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        TransactionTemplate txn = new TransactionTemplate(txManager);
        try {
            txn.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, "t_single");
                jdbc.update(
                        "INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                        doc, "t_single", "crash-doc", "text/plain");
                jdbc.update(
                        "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                        ver, "t_single", doc, "c".repeat(64));
                jdbc.update(
                        "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                        job, "t_single", doc, ver, "audit-crash-" + UUID.randomUUID());
                AuditChain.append(jdbc, "t_single", job, AuditChain.TYPE_SUBMIT,
                        Map.of("doc_id", doc.toString(), "version_id", ver.toString(), "sha256",
                                "c".repeat(64)));
                throw new RuntimeException("simulated crash between effect and commit");
            });
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("simulated crash");
        }
        // Both rolled back: no orphan job without audit, no orphan audit without job.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, job))
                .isEqualTo(0);
        assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE job_id = ?", Integer.class,
                        job))
                .isEqualTo(0);

        // Denied commit appends nothing and changes nothing (no orphan audit).
        var submitted = submitService.submit("t_single", "audit-denied-" + UUID.randomUUID(),
                "Denied", "text/plain", "denied-body");
        UUID job2 = submitted.jobId();
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        int before = chain("t_single", job2).size();
        // No grant -> CapDeniedException, 0 job rows, 0 audit rows.
        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job2, "w1", claimed.fencingToken()))
                .isInstanceOf(com.g1do.anvil.jobs.CapDeniedException.class);
        assertThat(chain("t_single", job2)).hasSize(before);
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job2))
                .isEqualTo("RUNNING");
    }

    @Test
    void missingTenantContextRendersZeroRows() {
        var submitted = submitService.submit("t_single", "audit-rls-" + UUID.randomUUID(),
                "Rls", "text/plain", "rls-body");
        assertThat(chain("t_single", submitted.jobId())).isNotEmpty();

        appPool = newAppPool();
        appJdbc = new JdbcTemplate(appPool);
        // No SET LOCAL: RLS fails closed with 0 rows, no error.
        Integer count = appJdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        assertThat(count).isEqualTo(0);
    }
}
