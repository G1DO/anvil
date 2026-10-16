package com.g1do.anvil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.audit.AuditReadService;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-isolated audit event read API proofs (issue #29).
 *
 * <p>Proves on containerized Postgres: own-tenant chain ordered by
 * {@code seq}, cross-tenant {@code job_id} denies without oracle (same empty
 * {@code 200 []} as missing), forged {@code tenant} param ignored, foreign
 * cursor replays to 0 rows, missing/unknown tenant is {@code 401}, malformed
 * {@code job_id}/cursor is {@code 400}, and the read path keeps
 * {@code SET LOCAL} + read-back + {@code WHERE tenant_id} + RLS
 * {@code FORCE} with JDBC only (no direct-FS paths).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditReadIsolationTest {

    private static final String TENANT_A = "t_auditread_a";
    private static final String TENANT_B = "t_auditread_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submitService;

    @Autowired
    JobService jobs;

    @Autowired
    AuditReadService auditReads;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PlatformTransactionManager txManager;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private UUID jobA;
    private UUID jobB;

    @BeforeEach
    void setUp() {
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

        // Two jobs with multi-event chains: submit -> claim -> cap.issue -> commit.
        var submittedA = submitService.submit(TENANT_A, "auditread-a-" + UUID.randomUUID(),
                "AuditRead A", "text/plain", "body-a");
        jobA = submittedA.jobId();
        ClaimedJob claimedA = jobs.claim(TENANT_A, "auditread-w-a").orElseThrow();
        assertThat(claimedA.jobId()).isEqualTo(jobA);
        var capA = jobs.issueCap(TENANT_A, jobA);
        assertThat(jobs.commitSucceeded(TENANT_A, jobA, "auditread-w-a", claimedA.fencingToken(),
                capA.capId(), capA.epoch())).isTrue();

        var submittedB = submitService.submit(TENANT_B, "auditread-b-" + UUID.randomUUID(),
                "AuditRead B", "text/plain", "body-b");
        jobB = submittedB.jobId();
        ClaimedJob claimedB = jobs.claim(TENANT_B, "auditread-w-b").orElseThrow();
        assertThat(claimedB.jobId()).isEqualTo(jobB);
        var capB = jobs.issueCap(TENANT_B, jobB);
        assertThat(jobs.commitSucceeded(TENANT_B, jobB, "auditread-w-b", claimedB.fencingToken(),
                capB.capId(), capB.epoch())).isTrue();
    }

    @Test
    void ownTenantChainOrderedBySeq() {
        List<AuditReadService.AuditEventRow> rows = auditReads.listEvents(TENANT_A, jobA, 100, null);
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().map(AuditReadService.AuditEventRow::seq))
                .containsExactly(0L, 1L, 2L, 3L);
        assertThat(rows.stream().map(AuditReadService.AuditEventRow::type))
                .containsExactly("submit", "claim", "cap.issue", "commit");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.tenantId()).isEqualTo(TENANT_A);
            assertThat(r.jobId()).isEqualTo(jobA);
        });
        // Hash linkage spot check: GENESIS then chained prev_hash.
        assertThat(rows.get(0).prevHash()).isEqualTo("GENESIS");
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).prevHash()).isEqualTo(rows.get(i - 1).eventHash());
        }
    }

    @Test
    void crossTenantJobIdDeniesWithoutOracle() {
        // Service: cross-tenant reads exactly like missing (empty, no content).
        List<AuditReadService.AuditEventRow> cross = auditReads.listEvents(TENANT_A, jobB, 100, null);
        List<AuditReadService.AuditEventRow> missing =
                auditReads.listEvents(TENANT_A, UUID.randomUUID(), 100, null);
        assertThat(cross).as("cross-tenant job_id").isEmpty();
        assertThat(missing).as("missing job_id").isEmpty();
        assertThat(cross).isEqualTo(missing);
        // Control: owner still sees its chain.
        assertThat(auditReads.listEvents(TENANT_B, jobB, 100, null)).isNotEmpty();
    }

    @Test
    void httpCrossTenantMatchesMissingShape() throws Exception {
        MvcResult cross = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobB.toString()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode crossTree = jsonMapper.readTree(cross.getResponse().getContentAsString());
        JsonNode missingTree = jsonMapper.readTree(missing.getResponse().getContentAsString());
        assertThat(crossTree.isArray()).isTrue();
        assertThat(crossTree.size()).isEqualTo(0);
        assertThat(missingTree.isArray()).isTrue();
        assertThat(missingTree.size()).isEqualTo(0);
        assertThat(crossTree.toString()).isEqualTo(missingTree.toString());

        // Control: own chain over HTTP is ordered by seq.
        MvcResult own = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobA.toString())
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ownTree = jsonMapper.readTree(own.getResponse().getContentAsString());
        assertThat(ownTree.size()).isEqualTo(4);
        assertThat(ownTree.get(0).get("seq").asLong()).isEqualTo(0L);
        assertThat(ownTree.get(3).get("seq").asLong()).isEqualTo(3L);
        for (JsonNode node : ownTree) {
            assertThat(node.get("tenant_id").asText()).isEqualTo(TENANT_A);
            assertThat(node.get("job_id").asText()).isEqualTo(jobA.toString());
        }
    }

    @Test
    void forgedTenantParamIgnored() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobA.toString())
                        .param("tenant", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(tree.size()).isGreaterThan(0);
        for (JsonNode node : tree) {
            assertThat(node.get("tenant_id").asText()).isEqualTo(TENANT_A);
        }

        MvcResult result2 = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobB.toString())
                        .param("tenant_id", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree2 = jsonMapper.readTree(result2.getResponse().getContentAsString());
        assertThat(tree2.size()).as("forged tenant still isolates cross job").isEqualTo(0);
    }

    @Test
    void foreignCursorReplaysToZeroRows() throws Exception {
        // Owner cursor works: page past the first event.
        List<AuditReadService.AuditEventRow> all = auditReads.listEvents(TENANT_B, jobB, 100, null);
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        UUID firstB = all.get(0).id();
        List<AuditReadService.AuditEventRow> next =
                auditReads.listEvents(TENANT_B, jobB, 100, firstB);
        assertThat(next).isNotEmpty();
        assertThat(next.get(0).seq()).isGreaterThan(all.get(0).seq());

        // Replay of B's cursor by A (same job scope impossible cross-tenant, so
        // resolve fails) returns 0 rows. Use B's event id with A's job: missing.
        List<AuditReadService.AuditEventRow> replay =
                auditReads.listEvents(TENANT_A, jobA, 100, firstB);
        assertThat(replay).as("B cursor replayed by A").isEmpty();

        // HTTP replay also returns 0 rows with identical empty shape.
        MvcResult http = mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobA.toString())
                        .param("cursor", firstB.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tree = jsonMapper.readTree(http.getResponse().getContentAsString());
        assertThat(tree.size()).isEqualTo(0);
    }

    @Test
    void missingOrUnknownTenantIs401() throws Exception {
        mockMvc.perform(get("/audit/events").param("job_id", jobA.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", "t_unknown_xyz")
                        .param("job_id", jobA.toString()))
                .andExpect(status().isUnauthorized());
        // Aliased route enforces the same.
        mockMvc.perform(get("/api/audit/events").param("job_id", jobA.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJobIdAndCursorAre400() throws Exception {
        mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/audit/events")
                        .header("X-Tenant-Id", TENANT_A)
                        .param("job_id", jobA.toString())
                        .param("cursor", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitsDefault20AndCap100() {
        assertThat(AuditReadService.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(AuditReadService.MAX_LIMIT).isEqualTo(100);
        assertThat(AuditReadService.normalizeLimit(null)).isEqualTo(20);
        assertThat(AuditReadService.normalizeLimit(0)).isEqualTo(20);
        assertThat(AuditReadService.normalizeLimit(500)).isEqualTo(100);
    }

    @Test
    void recheckDropsRaceInsertedCrossRows() {
        AuditReadService.AuditEventRow own = new AuditReadService.AuditEventRow(
                UUID.randomUUID(), TENANT_A, jobA, 0L, "GENESIS", "a".repeat(64),
                "submit", jsonMapper.createObjectNode(), null);
        AuditReadService.AuditEventRow forgedTenant = new AuditReadService.AuditEventRow(
                UUID.randomUUID(), TENANT_B, jobA, 0L, "GENESIS", "b".repeat(64),
                "submit", jsonMapper.createObjectNode(), null);
        AuditReadService.AuditEventRow forgedJob = new AuditReadService.AuditEventRow(
                UUID.randomUUID(), TENANT_A, jobB, 0L, "GENESIS", "c".repeat(64),
                "submit", jsonMapper.createObjectNode(), null);
        List<AuditReadService.AuditEventRow> kept =
                AuditReadService.recheck(TENANT_A, jobA, List.of(own, forgedTenant, forgedJob));
        assertThat(kept).containsExactly(own);
    }

    @Test
    void rlsCatalogProofForAuditReads() {
        Map<String, Object> rls = jdbc.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'audit_events'");
        assertThat(rls.get("relrowsecurity")).isEqualTo(true);
        assertThat(rls.get("relforcerowsecurity")).isEqualTo(true);
        String qual = jdbc.queryForObject(
                "SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = 'audit_events' "
                        + "AND policyname = 'tenant_isolation'",
                String.class);
        assertThat(qual).contains("current_setting('app.tenant'");
    }
}
