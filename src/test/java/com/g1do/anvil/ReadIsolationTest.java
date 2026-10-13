package com.g1do.anvil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.cas.CasPresignedService;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.read.ReadService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 28-case cross-tenant read matrix for issue #22.
 *
 * <p>Proves on containerized Postgres with Testcontainers: direct-ID (D01-D08),
 * search/listing (S01-S08), CAS (C01-C06), and presigned TTL-60s (P01-P06) all
 * deny without content, expose no existence oracle, and leak 0 rows.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReadIsolationTest {

    private static final String TENANT_A = "t_read_a";
    private static final String TENANT_B = "t_read_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @Autowired
    ReadService reads;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PlatformTransactionManager txManager;

    @TempDir
    Path tempDir;

    private ContentStore tempStore;
    private CasPresignedService presigned;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private UUID docA1;
    private UUID docA2;
    private UUID docB1;
    private UUID docB2;
    private UUID verA1;
    private UUID verB1;
    private UUID jobA1;
    private UUID jobB1;
    private UUID attemptA1;
    private UUID attemptB1;
    private String shaA1;
    private String shaB1;
    private UUID outboxA1;
    private UUID outboxB1;

    @BeforeEach
    void setUp() {
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

        tempStore = new ContentStore(jdbc, txManager, tempDir.toString());
        presigned = new CasPresignedService(
                jdbc, txManager, jobs, tempStore,
                "matrix-test-secret".getBytes(StandardCharsets.UTF_8));

        // Two docs per tenant with disjoint title prefixes for S01/S04/S07.
        docA1 = insertDocument(TENANT_A, "attacker-alpha-1");
        docA2 = insertDocument(TENANT_A, "attacker-alpha-2");
        docB1 = insertDocument(TENANT_B, "victim-bravo-1");
        docB2 = insertDocument(TENANT_B, "victim-bravo-2");

        verA1 = insertVersion(TENANT_A, docA1, "a".repeat(64));
        insertVersion(TENANT_A, docA2, "a".repeat(63) + "1");
        verB1 = insertVersion(TENANT_B, docB1, "b".repeat(64));
        insertVersion(TENANT_B, docB2, "b".repeat(63) + "1");

        jobA1 = insertJob(TENANT_A, docA1, verA1, "read-a-1");
        insertJob(TENANT_A, docA2, jdbc.queryForObject(
                "SELECT id FROM document_versions WHERE document_id = ?", UUID.class, docA2),
                "read-a-2");
        jobB1 = insertJob(TENANT_B, docB1, verB1, "read-b-1");
        insertJob(TENANT_B, docB2, jdbc.queryForObject(
                "SELECT id FROM document_versions WHERE document_id = ?", UUID.class, docB2),
                "read-b-2");

        attemptA1 = insertAttempt(TENANT_A, jobA1, 0);
        attemptB1 = insertAttempt(TENANT_B, jobB1, 0);

        shaA1 = "c".repeat(64);
        shaB1 = "d".repeat(64);
        jdbc.update("INSERT INTO artifacts (tenant_id, sha256, size_bytes, refcount) VALUES (?, ?, ?, 0)",
                TENANT_A, shaA1, 16);
        jdbc.update("INSERT INTO artifacts (tenant_id, sha256, size_bytes, refcount) VALUES (?, ?, ?, 0)",
                TENANT_B, shaB1, 16);

        outboxA1 = insertOutbox(TENANT_A, jobA1, docA1, verA1, shaA1);
        outboxB1 = insertOutbox(TENANT_B, jobB1, docB1, verB1, shaB1);
    }

    private UUID insertDocument(String tenant, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                id, tenant, title);
        return id;
    }

    private UUID insertVersion(String tenant, UUID docId, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                id, tenant, docId, sha);
        return id;
    }

    private UUID insertJob(String tenant, UUID docId, UUID verId, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                id, tenant, docId, verId, key + "-" + UUID.randomUUID());
        return id;
    }

    private UUID insertAttempt(String tenant, UUID jobId, int number) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO attempts (id, tenant_id, job_id, attempt_number, owner, fencing_token, outcome) VALUES (?, ?, ?, ?, ?, 0, 'CLAIMED')",
                id, tenant, jobId, number, "seeder-" + tenant);
        return id;
    }

    private UUID insertOutbox(String tenant, UUID jobId, UUID docId, UUID verId, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, sha256, payload) VALUES (?, ?, ?, ?, ?, 'job.committed', ?, '{}'::jsonb)",
                id, tenant, jobId, docId, verId, sha);
        return id;
    }

    // Direct-ID 8: all deny without content, no existence oracle.

    @Test
    void d01_documentCrossTenantFetchDenies() {
        assertThat(reads.getDocument(TENANT_A, docB1)).isEmpty();
        // No oracle: missing ID behaves identically (empty, no content).
        assertThat(reads.getDocument(TENANT_A, UUID.randomUUID())).isEmpty();
        // Control: own row still visible (no over-blocking).
        assertThat(reads.getDocument(TENANT_A, docA1)).isPresent();
        assertThat(reads.getDocument(TENANT_B, docB1)).isPresent();
    }

    @Test
    void d02_versionCrossTenantFetchDenies() {
        assertThat(reads.getVersion(TENANT_A, verB1)).isEmpty();
        assertThat(reads.getVersion(TENANT_A, UUID.randomUUID())).isEmpty();
        assertThat(reads.getVersion(TENANT_A, verA1)).isPresent();
        assertThat(reads.getVersion(TENANT_B, verB1)).isPresent();
    }

    @Test
    void d03_jobCrossTenantFetchDenies() {
        assertThat(reads.getJob(TENANT_A, jobB1)).isEmpty();
        assertThat(reads.getJob(TENANT_A, UUID.randomUUID())).isEmpty();
        assertThat(reads.getJob(TENANT_A, jobA1)).isPresent();
        assertThat(reads.getJob(TENANT_B, jobB1)).isPresent();
    }

    @Test
    void d04_attemptCrossTenantFetchDenies() {
        assertThat(reads.getAttempt(TENANT_A, attemptB1)).isEmpty();
        assertThat(reads.getAttempt(TENANT_A, UUID.randomUUID())).isEmpty();
        assertThat(reads.getAttempt(TENANT_A, attemptA1)).isPresent();
        assertThat(reads.getAttempt(TENANT_B, attemptB1)).isPresent();
    }

    @Test
    void d05_artifactRowCrossTenantFetchDenies() {
        assertThat(reads.getArtifact(TENANT_A, shaB1)).isEmpty();
        assertThat(reads.getArtifact(TENANT_A, "e".repeat(64))).isEmpty();
        assertThat(reads.getArtifact(TENANT_A, shaA1)).isPresent();
        assertThat(reads.getArtifact(TENANT_B, shaB1)).isPresent();
    }

    @Test
    void d06_outboxEventCrossTenantFetchDenies() {
        assertThat(reads.getOutboxEvent(TENANT_A, outboxB1)).isEmpty();
        assertThat(reads.getOutboxEvent(TENANT_A, UUID.randomUUID())).isEmpty();
        assertThat(reads.getOutboxEvent(TENANT_A, outboxA1)).isPresent();
        assertThat(reads.getOutboxEvent(TENANT_B, outboxB1)).isPresent();
    }

    @Test
    void d07_claimWithForgedTenantJobDenies() {
        // Isolate: fresh tenants with only a victim QUEUED job.
        String attacker = "t_d07_a_" + UUID.randomUUID().toString().substring(0, 8);
        String victim = "t_d07_b_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", attacker);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", victim);
        UUID vDoc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                vDoc, victim, "d07-victim");
        UUID vVer = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                vVer, victim, vDoc, "f".repeat(64));
        UUID vJob = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                vJob, victim, vDoc, vVer, "d07-" + UUID.randomUUID());

        var claimedByAttacker = jobs.claim(attacker, "d07-attacker");
        assertThat(claimedByAttacker).as("attacker claim must never yield victim job").isEmpty();

        var claimedByVictim = jobs.claim(victim, "d07-victim-w");
        assertThat(claimedByVictim).isPresent();
        assertThat(claimedByVictim.get().jobId()).isEqualTo(vJob);
    }

    @Test
    void d08_commitWithForgedTenantJobTokenDenies() {
        // Victim job claimed by victim; attacker forges tenant+job+token.
        var claimed = jobs.claim(TENANT_B, "d08-victim-w").orElseThrow();
        jobs.issueCap(TENANT_B, claimed.jobId());
        // Attacker holds no grant for (attacker, victimJob): denies via capability gate.
        long rejectedBefore = jobs.capRejectedCount();
        UUID victimJob = claimed.jobId();
        long victimToken = claimed.fencingToken();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", victimJob);
        assertThatThrownBy(() -> jobs.commitSucceeded(TENANT_A, victimJob, "d08-attacker", victimToken))
                .isInstanceOf(CapDeniedException.class)
                .satisfies(e -> {
                    CapDeniedException denied = (CapDeniedException) e;
                    assertThat(denied.getStatusCode().value()).isEqualTo(403);
                    assertThat(denied.getReason()).contains("NEEDS_APPROVAL");
                });
        assertThat(jobs.capRejectedCount()).isEqualTo(rejectedBefore + 1);
        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", victimJob);
        assertThat(after.get("state")).isEqualTo("RUNNING");
        assertThat(after.get("owner")).isEqualTo(before.get("owner"));
        assertThat(after.get("fencing_token")).isEqualTo(before.get("fencing_token"));
    }

    // Search/listing 8.

    @Test
    void s01_listDocumentsIsolatesToCaller() {
        List<ReadService.DocumentRow> asA = reads.searchDocuments(TENANT_A, null, 100, null);
        assertThat(asA).isNotEmpty();
        assertThat(asA).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_A));
        assertThat(asA.stream().map(ReadService.DocumentRow::title))
                .doesNotContain("victim-bravo-1", "victim-bravo-2");

        List<ReadService.DocumentRow> asB = reads.searchDocuments(TENANT_B, null, 100, null);
        assertThat(asB).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_B));
        assertThat(asB.stream().map(ReadService.DocumentRow::title))
                .doesNotContain("attacker-alpha-1", "attacker-alpha-2");
    }

    @Test
    void s02_listJobsIsolatesToCaller() {
        List<ReadService.JobRow> asA = reads.listJobs(TENANT_A, 100);
        assertThat(asA).isNotEmpty();
        assertThat(asA).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_A));
        assertThat(asA.stream().map(ReadService.JobRow::id)).doesNotContain(jobB1);

        List<ReadService.JobRow> asB = reads.listJobs(TENANT_B, 100);
        assertThat(asB).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_B));
        assertThat(asB.stream().map(ReadService.JobRow::id)).doesNotContain(jobA1);
    }

    @Test
    void s03_listArtifactsIsolatesToCaller() {
        List<ReadService.ArtifactRow> asA = reads.listArtifacts(TENANT_A, 100);
        assertThat(asA).isNotEmpty();
        assertThat(asA).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_A));
        assertThat(asA.stream().map(ReadService.ArtifactRow::sha256)).doesNotContain(shaB1);

        List<ReadService.ArtifactRow> asB = reads.listArtifacts(TENANT_B, 100);
        assertThat(asB).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_B));
        assertThat(asB.stream().map(ReadService.ArtifactRow::sha256)).doesNotContain(shaA1);
    }

    @Test
    void s04_titlePrefixQueryIsolates() {
        List<ReadService.DocumentRow> attackerSeesVictim =
                reads.searchDocuments(TENANT_A, "victim-bravo", 100, null);
        assertThat(attackerSeesVictim).as("attacker prefix query for victim titles").isEmpty();

        List<ReadService.DocumentRow> victimSeesOwn =
                reads.searchDocuments(TENANT_B, "victim-bravo", 100, null);
        assertThat(victimSeesOwn).hasSize(2);
        assertThat(victimSeesOwn).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_B));

        List<ReadService.DocumentRow> attackerSeesOwn =
                reads.searchDocuments(TENANT_A, "attacker-alpha", 100, null);
        assertThat(attackerSeesOwn).hasSize(2);
    }

    @Test
    void s04_blankPrefixMatchesAllLikeEmpty() {
        List<ReadService.DocumentRow> all = reads.searchDocuments(TENANT_A, null, 100, null);
        List<ReadService.DocumentRow> blank = reads.searchDocuments(TENANT_A, "   ", 100, null);
        assertThat(blank).as("blank prefix matches all caller rows").hasSameSizeAs(all);
        assertThat(blank.stream().map(ReadService.DocumentRow::id))
                .containsExactlyInAnyOrderElementsOf(
                        all.stream().map(ReadService.DocumentRow::id).toList());
        assertThat(blank).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_A));
    }

    @Test
    void s05_omittedTenantParamDefaultsToHeaderFailClosed() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/documents")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("query", "")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(tree.isArray()).isTrue();
        assertThat(tree.size()).isGreaterThan(0);
        for (JsonNode node : tree) {
            assertThat(node.get("tenant_id").asText()).isEqualTo(TENANT_A);
            assertThat(node.get("title").asText()).doesNotContain("victim");
        }
    }

    @Test
    void s06_forgedTenantInQueryIgnored() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/documents")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("query", "")
                        .param("limit", "100")
                        .param("tenant", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(tree.size()).isGreaterThan(0);
        for (JsonNode node : tree) {
            assertThat(node.get("tenant_id").asText()).isEqualTo(TENANT_A);
        }

        MvcResult result2 = mockMvc.perform(get("/api/documents")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("query", "victim-bravo")
                        .param("limit", "100")
                        .param("tenant_id", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree2 = jsonMapper.readTree(result2.getResponse().getContentAsString());
        assertThat(tree2.size()).as("forged tenant prefix still isolates").isEqualTo(0);
    }

    @Test
    void s07_replayOfBCursorByAReturnsZeroRows() {
        // Owner cursor works: B pages past its first doc to its second.
        List<ReadService.DocumentRow> bAll = reads.searchDocuments(TENANT_B, null, 100, null);
        assertThat(bAll).hasSizeGreaterThanOrEqualTo(2);
        UUID firstB = bAll.get(0).id();
        List<ReadService.DocumentRow> bNext = reads.searchDocuments(TENANT_B, null, 100, firstB);
        assertThat(bNext).isNotEmpty();
        assertThat(bNext).allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(TENANT_B));

        // Replay of B's cursor by A returns 0 rows (fail closed).
        List<ReadService.DocumentRow> replay = reads.searchDocuments(TENANT_A, null, 100, firstB);
        assertThat(replay).as("B cursor replayed by A").isEmpty();

        // HTTP replay also returns 0 rows.
        try {
            MvcResult http = mockMvc.perform(get("/api/documents")
                            .header("X-Tenant-Id", TENANT_A)
                            .param("query", "")
                            .param("limit", "100")
                            .param("cursor", firstB.toString()))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode tree = jsonMapper.readTree(http.getResponse().getContentAsString());
            assertThat(tree.size()).isEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void s08_prefilterPlusRecheckDropsRaceInsertedCrossRow() {
        ReadService.DocumentRow own =
                new ReadService.DocumentRow(UUID.randomUUID(), TENANT_A, "own", "text/plain", null);
        ReadService.DocumentRow forged =
                new ReadService.DocumentRow(UUID.randomUUID(), TENANT_B, "forged", "text/plain", null);
        List<ReadService.DocumentRow> kept = ReadService.recheck(TENANT_A, List.of(own, forged));
        assertThat(kept).containsExactly(own);
        assertThat(kept.stream().map(ReadService.DocumentRow::tenantId)).doesNotContain(TENANT_B);
    }

    // CAS 6.

    @Test
    void c01_victimPathAsAttackerDenies() throws Exception {
        byte[] victimBytes = "c01-victim-bytes".getBytes(StandardCharsets.UTF_8);
        String shaV = tempStore.put(TENANT_B, victimBytes);

        assertThatThrownBy(() -> tempStore.getForCaller(TENANT_A, TENANT_B, shaV))
                .isInstanceOf(NoSuchFileException.class)
                .satisfies(e -> {
                    // No content in the message; caller-scoped address (no oracle).
                    assertThat(e.getMessage()).doesNotContain(new String(victimBytes, StandardCharsets.UTF_8));
                    assertThat(e.getMessage()).contains(TENANT_A);
                });
        // Control: owner reads back.
        assertThat(tempStore.getForCaller(TENANT_B, TENANT_B, shaV)).isEqualTo(victimBytes);
    }

    @Test
    void c02_sameShaNotInAttackerNamespace404NoOracle() throws Exception {
        byte[] victimBytes = "c02-victim-only".getBytes(StandardCharsets.UTF_8);
        String shaV = tempStore.put(TENANT_B, victimBytes);

        NoSuchFileException cross = null;
        try {
            tempStore.getForCaller(TENANT_A, TENANT_A, shaV);
        } catch (NoSuchFileException e) {
            cross = e;
        }
        assertThat(cross).isNotNull();

        NoSuchFileException missing = null;
        String randomSha = "e".repeat(64);
        try {
            tempStore.getForCaller(TENANT_A, TENANT_A, randomSha);
        } catch (NoSuchFileException e) {
            missing = e;
        }
        assertThat(missing).isNotNull();
        // No oracle: same exception type, same caller-scoped shape, no content.
        assertThat(cross.getClass()).isEqualTo(missing.getClass());
        assertThat(cross.getMessage()).contains(TENANT_A);
        assertThat(missing.getMessage()).contains(TENANT_A);
    }

    @Test
    void c03_dotdotTraversalDenies() {
        assertThatThrownBy(() -> tempStore.get("../evil", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tempStore.getForCaller(TENANT_A, "../evil", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tempStore.getForCaller("../evil", TENANT_A, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempDir.resolve("evil")).doesNotExist();
    }

    @Test
    void c04_encodedTraversalDenies() {
        assertThatThrownBy(() -> tempStore.get("%2e%2e", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tempStore.get("a%252e", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tempStore.getForCaller(TENANT_A, "%2E%2E", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tempStore.get(TENANT_A, "%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e%2e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void c05_sameBytesBothTenantsTwoFilesGcIsolated() throws Exception {
        byte[] same = "c05-same-bytes-both-tenants".getBytes(StandardCharsets.UTF_8);
        String shaA = tempStore.put(TENANT_A, same);
        String shaB = tempStore.put(TENANT_B, same);
        assertThat(shaA).isEqualTo(shaB);

        Path pathA = tempStore.pathFor(TENANT_A, shaA);
        Path pathB = tempStore.pathFor(TENANT_B, shaB);
        assertThat(pathA).isNotEqualTo(pathB);
        assertThat(Files.isRegularFile(pathA)).isTrue();
        assertThat(Files.isRegularFile(pathB)).isTrue();

        // Age only A's row past GC grace; B stays young.
        jdbc.update("UPDATE artifacts SET created_at = clock_timestamp() - INTERVAL '2 hours' "
                + "WHERE tenant_id = ? AND sha256 = ?", TENANT_A, shaA);

        tempStore.collectGarbage();

        assertThat(Files.exists(pathA)).as("aged unreferenced A collected").isFalse();
        assertThat(Files.exists(pathB)).as("young B retained").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                Integer.class, TENANT_B, shaB)).isEqualTo(1);
        assertThat(tempStore.getForCaller(TENANT_B, TENANT_B, shaB)).isEqualTo(same);
    }

    @Test
    void c06_directFsBypassDenies() throws Exception {
        byte[] victimBytes = "c06-victim-secret".getBytes(StandardCharsets.UTF_8);
        String shaV = tempStore.put(TENANT_B, victimBytes);

        // Attacker learns the raw path; OS-level read would succeed (file exists).
        Path victimPath = tempStore.pathFor(TENANT_B, shaV);
        assertThat(Files.readAllBytes(victimPath)).isEqualTo(victimBytes);

        // API gateway still denies: mismatched namespace and missing attacker row.
        assertThatThrownBy(() -> tempStore.getForCaller(TENANT_A, TENANT_B, shaV))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> tempStore.getForCaller(TENANT_A, TENANT_A, shaV))
                .isInstanceOf(NoSuchFileException.class);
    }

    // Presigned TTL 60s 6.

    private UUID presignedJob(String tenant) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                doc, tenant, "presign-" + doc.toString().substring(0, 8));
        UUID ver = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, tenant, doc, "f".repeat(64));
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, "presign-" + UUID.randomUUID());
        return job;
    }

    @Test
    void p01_issueForVictimShaAsAttacker403() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] victimBytes = "p01-victim".getBytes(StandardCharsets.UTF_8);
        String shaV = tempStore.put(TENANT_B, victimBytes);

        assertThatThrownBy(() -> presigned.issue(TENANT_A, jobA, shaV))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode().value()).isEqualTo(403);
                });

        // Control: own sha issues.
        byte[] own = "p01-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);
        String token = presigned.issue(TENANT_A, jobA, shaOwn);
        assertThat(token).isNotBlank();
        assertThat(presigned.authorize(TENANT_A, token).sha256()).isEqualTo(shaOwn);
    }

    @Test
    void p02_attackerUrlSwappedToVictim403() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] own = "p02-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);
        String tokenA = presigned.issue(TENANT_A, jobA, shaOwn);

        // Same token presented as another tenant denies (tenant mismatch).
        assertThatThrownBy(() -> presigned.authorize(TENANT_B, tokenA))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));

        // Payload tenant swapped to victim without re-signing also denies (signature).
        String[] parts = tokenA.split("\\.", -1);
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode payload = jsonMapper.readTree(payloadBytes);
        String swappedJson = jsonMapper.writeValueAsString(
                jsonMapper.createObjectNode()
                        .put("t", TENANT_B)
                        .put("sha", payload.get("sha").asText())
                        .put("job", payload.get("job").asText())
                        .put("cap", payload.get("cap").asText())
                        .put("epoch", payload.get("epoch").asLong())
                        .put("exp", payload.get("exp").asLong()));
        String swappedBase64 =
                Base64.getUrlEncoder().withoutPadding().encodeToString(swappedJson.getBytes(StandardCharsets.UTF_8));
        String swappedToken = swappedBase64 + "." + parts[1];
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, swappedToken))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void p03_replayAfterRevoke403NeedsApprovalPlusCounter() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] own = "p03-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);
        String token = presigned.issue(TENANT_A, jobA, shaOwn);
        // Valid before revoke.
        assertThat(presigned.authorize(TENANT_A, token).sha256()).isEqualTo(shaOwn);

        jobs.revokeCap(TENANT_A, jobA);
        long rejectedBefore = jobs.capRejectedCount();
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, token))
                .isInstanceOf(CapDeniedException.class)
                .satisfies(e -> {
                    CapDeniedException denied = (CapDeniedException) e;
                    assertThat(denied.getStatusCode().value()).isEqualTo(403);
                    assertThat(denied.getReason()).contains("NEEDS_APPROVAL");
                });
        assertThat(jobs.capRejectedCount()).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void p04_expired403() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] own = "p04-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);

        // TTL contract is 60s.
        assertThat(CasPresignedService.TTL_SECONDS).isEqualTo(60L);

        long pastExp = Instant.now().minusSeconds(120).getEpochSecond();
        String expired = presigned.issueAtExpiry(TENANT_A, jobA, shaOwn, pastExp);
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, expired))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void p04_farFutureExpiryCappedToTtl() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] own = "p04-cap-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);

        long farFuture = Instant.now().getEpochSecond() + 365L * 24 * 3600;
        String token = presigned.issueAtExpiry(TENANT_A, jobA, shaOwn, farFuture);
        String[] parts = token.split("\\.", -1);
        JsonNode payload = jsonMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        long exp = payload.get("exp").asLong();
        long dbNow = jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class)
                .toEpochSecond();
        // Capped to DB now + 60s (tolerance for the two DB-clock reads), not a year out.
        assertThat(exp).isLessThanOrEqualTo(dbNow + CasPresignedService.TTL_SECONDS + 5);
        assertThat(exp).isGreaterThan(dbNow);
        // Capped token still authorizes short-term with the capped expiry.
        assertThat(presigned.authorize(TENANT_A, token).expiresAtEpoch()).isEqualTo(exp);
    }

    @Test
    void p05_tamperedShaExpirySignature403() throws Exception {
        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        byte[] own = "p05-own".getBytes(StandardCharsets.UTF_8);
        String shaOwn = tempStore.put(TENANT_A, own);
        String token = presigned.issue(TENANT_A, jobA, shaOwn);
        String[] parts = token.split("\\.", -1);
        assertThat(parts).hasSize(2);
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode payload = jsonMapper.readTree(payloadBytes);

        // Tampered sha (valid shape, wrong value) without re-signing.
        String shaJson = jsonMapper.writeValueAsString(
                jsonMapper.createObjectNode()
                        .put("t", payload.get("t").asText())
                        .put("sha", "a".repeat(64))
                        .put("job", payload.get("job").asText())
                        .put("cap", payload.get("cap").asText())
                        .put("epoch", payload.get("epoch").asLong())
                        .put("exp", payload.get("exp").asLong()));
        String shaTampered = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(shaJson.getBytes(StandardCharsets.UTF_8))
                + "." + parts[1];
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, shaTampered))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));

        // Tampered expiry without re-signing.
        String expJson = jsonMapper.writeValueAsString(
                jsonMapper.createObjectNode()
                        .put("t", payload.get("t").asText())
                        .put("sha", payload.get("sha").asText())
                        .put("job", payload.get("job").asText())
                        .put("cap", payload.get("cap").asText())
                        .put("epoch", payload.get("epoch").asLong())
                        .put("exp", payload.get("exp").asLong() + 9999));
        String expTampered = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(expJson.getBytes(StandardCharsets.UTF_8))
                + "." + parts[1];
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, expTampered))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));

        // Tampered signature.
        String last = parts[1];
        char flipped = last.charAt(last.length() - 1) == '0' ? '1' : '0';
        String sigTampered = parts[0] + "." + last.substring(0, last.length() - 1) + flipped;
        assertThatThrownBy(() -> presigned.authorize(TENANT_A, sigTampered))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void p06_attackerReuploadSameBytesGetsAttackerScopedUrlOnly() throws Exception {
        byte[] same = "p06-same-bytes".getBytes(StandardCharsets.UTF_8);
        String shaAttacker = tempStore.put(TENANT_A, same);
        String shaVictim = tempStore.put(TENANT_B, same);
        assertThat(shaAttacker).isEqualTo(shaVictim);

        UUID jobA = presignedJob(TENANT_A);
        jobs.issueCap(TENANT_A, jobA);
        String tokenA = presigned.issue(TENANT_A, jobA, shaAttacker);

        CasPresignedService.PresignedGrant grant = presigned.authorize(TENANT_A, tokenA);
        assertThat(grant.tenantId()).isEqualTo(TENANT_A);
        assertThat(grant.sha256()).isEqualTo(shaAttacker);
        assertThat(presigned.fetch(TENANT_A, tokenA)).isEqualTo(same);

        // Attacker-scoped URL never authorizes as victim.
        assertThatThrownBy(() -> presigned.authorize(TENANT_B, tokenA))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
        assertThatThrownBy(() -> presigned.fetch(TENANT_B, tokenA))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ttlContractIs60s() {
        assertThat(CasPresignedService.TTL_SECONDS).isEqualTo(60L);
        assertThat(ReadService.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(ReadService.MAX_LIMIT).isEqualTo(100);
    }

    @Test
    void httpSearchRequiresAuthWithoutLeak() throws Exception {
        mockMvc.perform(get("/api/documents").param("query", "").param("limit", "10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/documents")
                        .header("X-Tenant-Id", "t_unknown_xyz")
                        .param("query", "")
                        .param("limit", "10"))
                .andExpect(status().isUnauthorized());
    }
}
