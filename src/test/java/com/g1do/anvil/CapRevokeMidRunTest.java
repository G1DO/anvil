package com.g1do.anvil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mid-run revoke proof for capability-gated commits (issue #21).
 *
 * <p>Proves on containerized Postgres: claim -&gt; revoke/expire/supersede
 * mid-run -&gt; commit denies atomically (0 rows, {@code 403 NEEDS_APPROVAL},
 * {@code cap_rejected} incremented) while a valid-cap control still commits;
 * caps are tenant-bound (A cannot authorize B even with valid epoch/expiry).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CapRevokeMidRunTest {

    private static final String TENANT_A = "t_cap_a";
    private static final String TENANT_B = "t_cap_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @BeforeEach
    void clean() {
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

    private UUID createDocument(String tenant) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                id, tenant, "title-" + id.toString().substring(0, 8), "text/plain");
        return id;
    }

    private UUID createVersion(String tenant, UUID documentId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                id, tenant, documentId, "e".repeat(64));
        return id;
    }

    private UUID seedQueued(String tenant, String key) {
        UUID doc = createDocument(tenant);
        UUID ver = createVersion(tenant, doc);
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) "
                        + "VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, key);
        return job;
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", jobId);
    }

    private void assertCapDeniedWith403(Runnable commit, long rejectedBefore, UUID jobId) {
        Map<String, Object> before = jobRow(jobId);
        assertThatThrownBy(commit::run)
                .isInstanceOf(CapDeniedException.class)
                .satisfies(e -> {
                    CapDeniedException denied = (CapDeniedException) e;
                    assertThat(denied.getStatusCode().value()).isEqualTo(403);
                    assertThat(denied.getReason()).contains("NEEDS_APPROVAL");
                });
        assertThat(jobs.capRejectedCount())
                .as("cap_rejected incremented")
                .isEqualTo(rejectedBefore + 1);
        // 0 rows committed: state, owner, token unchanged.
        Map<String, Object> after = jobRow(jobId);
        assertThat(after.get("state")).isEqualTo("RUNNING");
        assertThat(after.get("owner")).isEqualTo(before.get("owner"));
        assertThat(after.get("fencing_token")).isEqualTo(before.get("fencing_token"));
    }

    @Test
    void revokedCapMidRunDeniesCommitAtomically() {
        UUID job = seedQueued("t_single", "cap-revoke-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        CapGrant cap = jobs.issueCap("t_single", job);

        // Mid-run revoke after claim+issue, before commit.
        jobs.revokeCap("t_single", job);

        long rejectedBefore = jobs.capRejectedCount();
        assertCapDeniedWith403(
                () -> jobs.commitSucceeded(
                        "t_single", job, "w1", claimed.fencingToken(), cap.capId(), cap.epoch()),
                rejectedBefore, job);

        // Current-grant path also denies once revoked.
        long rejectedBeforeCurrent = jobs.capRejectedCount();
        Map<String, Object> before = jobRow(job);
        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "w1", claimed.fencingToken()))
                .isInstanceOf(CapDeniedException.class);
        assertThat(jobs.capRejectedCount()).isEqualTo(rejectedBeforeCurrent + 1);
        assertThat(jobRow(job).get("state")).isEqualTo("RUNNING");
        assertThat(jobRow(job).get("owner")).isEqualTo(before.get("owner"));
    }

    @Test
    void expiredCapDeniesCommit() {
        UUID job = seedQueued("t_single", "cap-expire-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        CapGrant cap = jobs.issueCap("t_single", job);

        // Expire via DB clock only (never worker clock).
        jdbc.update("UPDATE cap_grants SET expires_at = clock_timestamp() - INTERVAL '1 second' "
                + "WHERE tenant_id = ? AND job_id = ?", "t_single", job);

        long rejectedBefore = jobs.capRejectedCount();
        assertCapDeniedWith403(
                () -> jobs.commitSucceeded(
                        "t_single", job, "w1", claimed.fencingToken(), cap.capId(), cap.epoch()),
                rejectedBefore, job);
    }

    @Test
    void supersededCapDeniesOldEpochWhileNewCommits() {
        UUID job = seedQueued("t_single", "cap-super-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        CapGrant first = jobs.issueCap("t_single", job);
        CapGrant second = jobs.issueCap("t_single", job);

        // Re-issue supersedes: same grant id, epoch bumped.
        assertThat(second.capId()).isEqualTo(first.capId());
        assertThat(second.epoch()).isEqualTo(first.epoch() + 1);

        // Old epoch denies as superseded, 0 rows.
        long rejectedBefore = jobs.capRejectedCount();
        assertCapDeniedWith403(
                () -> jobs.commitSucceeded(
                        "t_single", job, "w1", claimed.fencingToken(), first.capId(), first.epoch()),
                rejectedBefore, job);

        // Current epoch still commits normally.
        assertThat(jobs.commitSucceeded(
                "t_single", job, "w1", claimed.fencingToken(), second.capId(), second.epoch()))
                .isTrue();
        assertThat(jobRow(job).get("state")).isEqualTo("SUCCEEDED");
    }

    @Test
    void validCapControlStillCommits() {
        // Explicit-cap control.
        UUID job = seedQueued("t_single", "cap-valid-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        CapGrant cap = jobs.issueCap("t_single", job);
        assertThat(jobs.commitSucceeded(
                "t_single", job, "w1", claimed.fencingToken(), cap.capId(), cap.epoch()))
                .isTrue();
        assertThat(jobRow(job).get("state")).isEqualTo("SUCCEEDED");

        // Current-grant control (4-arg path with a valid grant).
        UUID job2 = seedQueued("t_single", "cap-valid4-" + UUID.randomUUID());
        ClaimedJob claimed2 = jobs.claim("t_single", "w1").orElseThrow();
        assertThat(claimed2.jobId()).isEqualTo(job2);
        jobs.issueCap("t_single", job2);
        assertThat(jobs.commitFailed("t_single", job2, "w1", claimed2.fencingToken())).isTrue();
        assertThat(jobRow(job2).get("state")).isEqualTo("FAILED");
    }

    @Test
    void crossTenantCapCannotAuthorizeEvenWithValidEpoch() {
        UUID jobA = seedQueued(TENANT_A, "cap-x-a-" + UUID.randomUUID());
        UUID jobB = seedQueued(TENANT_B, "cap-x-b-" + UUID.randomUUID());
        ClaimedJob claimedA = jobs.claim(TENANT_A, "w-a").orElseThrow();
        assertThat(claimedA.jobId()).isEqualTo(jobA);
        ClaimedJob claimedB = jobs.claim(TENANT_B, "w-b").orElseThrow();
        assertThat(claimedB.jobId()).isEqualTo(jobB);

        // Valid grant for tenant A (not revoked, future expiry).
        CapGrant capA = jobs.issueCap(TENANT_A, jobA);

        // Present A's cap for B's job with B's tenant: denies despite valid epoch/expiry.
        long rejectedBefore = jobs.capRejectedCount();
        Map<String, Object> beforeB = jobRow(jobB);
        assertThatThrownBy(() -> jobs.commitSucceeded(
                TENANT_B, jobB, "w-b", claimedB.fencingToken(), capA.capId(), capA.epoch()))
                .isInstanceOf(CapDeniedException.class)
                .satisfies(e -> {
                    CapDeniedException denied = (CapDeniedException) e;
                    assertThat(denied.getStatusCode().value()).isEqualTo(403);
                    assertThat(denied.getReason()).contains("NEEDS_APPROVAL");
                });
        assertThat(jobs.capRejectedCount()).isEqualTo(rejectedBefore + 1);
        Map<String, Object> afterB = jobRow(jobB);
        assertThat(afterB.get("state")).isEqualTo("RUNNING");
        assertThat(afterB.get("owner")).isEqualTo(beforeB.get("owner"));
        assertThat(afterB.get("fencing_token")).isEqualTo(beforeB.get("fencing_token"));

        // Control: B's own valid grant still commits.
        CapGrant capB = jobs.issueCap(TENANT_B, jobB);
        assertThat(jobs.commitSucceeded(
                TENANT_B, jobB, "w-b", claimedB.fencingToken(), capB.capId(), capB.epoch()))
                .isTrue();
        assertThat(jobRow(jobB).get("state")).isEqualTo("SUCCEEDED");
    }

    @Test
    void capGrantsDbEnforcement() {
        // Table + tenant-bound shape.
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = 'cap_grants'",
                String.class);
        assertThat(tables).containsExactly("cap_grants");

        // UNIQUE(tenant_id, job_id) keeps one authoritative grant per job (no
        // cross-tenant dedup: tenant_id is part of the uniqueness scope).
        Integer uniqueCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'cap_grants' "
                        + "AND indexdef LIKE '%(tenant_id, job_id)%'",
                Integer.class);
        assertThat(uniqueCount).isGreaterThanOrEqualTo(1);

        // Composite FK (tenant_id, job_id) -> jobs prevents cross-tenant references.
        Integer fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                        + "WHERE tc.table_name = 'cap_grants' AND tc.constraint_type = 'FOREIGN KEY'",
                Integer.class);
        assertThat(fkCount).isGreaterThanOrEqualTo(1);

        // RLS FORCE + tenant isolation policy (mirrors all other tenant tables).
        Map<String, Object> rls = jdbc.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'cap_grants'");
        assertThat(rls.get("relrowsecurity")).isEqualTo(true);
        assertThat(rls.get("relforcerowsecurity")).isEqualTo(true);
        String qual = jdbc.queryForObject(
                "SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = 'cap_grants' "
                        + "AND policyname = 'tenant_isolation'",
                String.class);
        assertThat(qual).contains("current_setting('app.tenant'");
        String check = jdbc.queryForObject(
                "SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'cap_grants' "
                        + "AND policyname = 'tenant_isolation'",
                String.class);
        assertThat(check).contains("current_setting('app.tenant'");

        // Least privilege: migrator owns, app has DML.
        String owner = jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'cap_grants'",
                String.class);
        assertThat(owner).isEqualTo("anvil_migrator");

        // Cross-tenant reference rejected at the database: grant for tenant A
        // cannot reference tenant B's job (composite FK).
        UUID jobB = seedQueued(TENANT_B, "cap-fk-" + UUID.randomUUID());
        UUID docA = createDocument(TENANT_A);
        UUID verA = createVersion(TENANT_A, docA);
        UUID jobA = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                jobA, TENANT_A, docA, verA, "cap-fk-a-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cap_grants (tenant_id, job_id, epoch, revoked, expires_at) VALUES (?, ?, 1, FALSE, clock_timestamp() + INTERVAL '1 hour')",
                TENANT_A, jobB))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
