package com.g1do.anvil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DurableV1SchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM attempts");
        jdbc.execute("DELETE FROM jobs");
        jdbc.execute("DELETE FROM document_versions");
        jdbc.execute("DELETE FROM documents");
        jdbc.execute("DELETE FROM artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
    }

    private void ensureTenant(String tenant) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
    }

    private UUID createDocument(String tenant) {
        ensureTenant(tenant);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                id, tenant, "title-" + id.toString().substring(0, 8), "text/plain");
        return id;
    }

    private UUID createVersion(String tenant, UUID documentId, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, ?, ?)",
                id, tenant, documentId, 1, sha);
        return id;
    }

    private UUID createJob(String tenant, UUID documentId, UUID versionId, String key, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, ?)",
                id, tenant, documentId, versionId, key, state);
        return id;
    }

    private UUID createFullJob(String tenant, String key, String state, String shaSeed) {
        UUID doc = createDocument(tenant);
        UUID ver = createVersion(tenant, doc, shaSeed);
        return createJob(tenant, doc, ver, key, state);
    }

    @Test
    void migrationsAppliedAndTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename IN "
                        + "('tenants','documents','document_versions','jobs','attempts','artifacts','outbox','processed_events')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "tenants", "documents", "document_versions", "jobs",
                "attempts", "artifacts", "outbox", "processed_events");

        Integer tSingle = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE id = 't_single'", Integer.class);
        assertThat(tSingle).isEqualTo(1);

        Integer history = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class);
        assertThat(history).isEqualTo(1);
    }

    @Test
    void everyRowCarriesTenantIdentity() {
        List<String> tables = List.of(
                "documents", "document_versions", "jobs", "attempts", "artifacts", "outbox", "processed_events");
        for (String table : tables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                            + "AND table_name = ? AND column_name = 'tenant_id' AND is_nullable = 'NO'",
                    Integer.class, table);
            assertThat(count)
                    .as("table %s must carry NOT NULL tenant_id", table)
                    .isEqualTo(1);
        }
    }

    @Test
    void uniqueTenantIdempotencyKeyEnforced() {
        String sha = "a".repeat(64);
        createFullJob("t_single", "key-dupe-1", "QUEUED", sha);

        UUID doc2 = createDocument("t_single");
        UUID ver2 = createVersion("t_single", doc2, "b".repeat(64));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), "t_single", doc2, ver2, "key-dupe-1", "QUEUED"))
                .isInstanceOf(DataAccessException.class);

        // Different key in same tenant succeeds.
        UUID other = createJob("t_single", doc2, ver2, "key-dupe-2", "QUEUED");
        assertThat(other).isNotNull();

        // Same key in a different tenant succeeds: scope is (tenant_id, idempotency_key).
        ensureTenant("t_other");
        UUID docOther = createDocument("t_other");
        UUID verOther = createVersion("t_other", docOther, "c".repeat(64));
        UUID crossTenant = createJob("t_other", docOther, verOther, "key-dupe-1", "QUEUED");
        assertThat(crossTenant).isNotNull();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE tenant_id = 't_single' AND idempotency_key = 'key-dupe-1'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void illegalTransitionFailsAtDatabase() {
        UUID job = createFullJob("t_single", "key-illegal-" + UUID.randomUUID(), "QUEUED", "d".repeat(64));

        // QUEUED -> SUCCEEDED skips RUNNING: illegal, must fail at the database.
        assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET state = 'SUCCEEDED' WHERE id = ?", job))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("illegal job transition");

        // Legal path still works: QUEUED -> RUNNING -> SUCCEEDED.
        jdbc.update("UPDATE jobs SET state = 'RUNNING', owner = 'w1', fencing_token = 1, "
                + "lease_expires_at = clock_timestamp() + interval '10 seconds' WHERE id = ?", job);
        String state = jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job);
        assertThat(state).isEqualTo("RUNNING");
        jdbc.update("UPDATE jobs SET state = 'SUCCEEDED' WHERE id = ?", job);
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void terminalJobsImmutableAndNeverRunnable() {
        UUID claimable = createFullJob("t_single", "key-claim-" + UUID.randomUUID(), "QUEUED", "e".repeat(64));
        UUID terminal = createFullJob("t_single", "key-term-" + UUID.randomUUID(), "QUEUED", "f".repeat(64));
        // Move terminal through the legal path to a terminal state.
        jdbc.update("UPDATE jobs SET state = 'RUNNING', owner = 'w1', fencing_token = 1, "
                + "lease_expires_at = clock_timestamp() + interval '10 seconds' WHERE id = ?", terminal);
        jdbc.update("UPDATE jobs SET state = 'SUCCEEDED' WHERE id = ?", terminal);

        // Marking a terminal job runnable fails at the database.
        assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET state = 'QUEUED' WHERE id = ?", terminal))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal states are immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET state = 'RUNNING' WHERE id = ?", terminal))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal states are immutable");

        // Seeded terminal is absent from claim results while claimable is present.
        List<UUID> claimableRows = jdbc.query(
                "SELECT id FROM claimable_jobs WHERE tenant_id = 't_single'",
                (rs, i) -> (UUID) rs.getObject("id"));
        assertThat(claimableRows).contains(claimable);
        assertThat(claimableRows).doesNotContain(terminal);

        List<UUID> activeRows = jdbc.query(
                "SELECT id FROM active_jobs WHERE tenant_id = 't_single'",
                (rs, i) -> (UUID) rs.getObject("id"));
        assertThat(activeRows).contains(claimable);
        assertThat(activeRows).doesNotContain(terminal);
    }

    @Test
    void terminalRowsFullyImmutable() {
        for (String terminalState : List.of("SUCCEEDED", "FAILED", "DEAD")) {
            UUID job = createFullJob("t_single", "key-imm-" + terminalState + "-" + UUID.randomUUID(),
                    "QUEUED", "0".repeat(63) + "1");
            if (terminalState.equals("SUCCEEDED") || terminalState.equals("FAILED")) {
                jdbc.update("UPDATE jobs SET state = 'RUNNING', owner = 'w1', fencing_token = 1, "
                        + "lease_expires_at = clock_timestamp() + interval '10 seconds' WHERE id = ?", job);
                jdbc.update("UPDATE jobs SET state = ? WHERE id = ?", terminalState, job);
            } else {
                // QUEUED -> DEAD is legal directly (reconciler exhausts queued).
                jdbc.update("UPDATE jobs SET state = 'DEAD' WHERE id = ?", job);
            }
            UUID id = job;
            // Even a same-state touch (zombie mutating owner/lease) must fail.
            assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET owner = 'zombie' WHERE id = ?", id))
                    .as("terminal %s row must reject same-state mutation", terminalState)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("terminal states are immutable");
        }
    }

    @Test
    void crossTenantReferencesRejected() {
        ensureTenant("t_other");
        UUID docSingle = createDocument("t_single");
        createVersion("t_single", docSingle, "1".repeat(64));
        UUID docOther = createDocument("t_other");
        UUID verOther = createVersion("t_other", docOther, "2".repeat(64));

        // Job in t_other must not reference a document/version owned by t_single.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), "t_other", docSingle, verOther, "key-x-" + UUID.randomUUID(), "QUEUED"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void claimAndRelayIndexesExistAndSupportQueries() {
        Set<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class)
                .stream().collect(Collectors.toSet());
        assertThat(indexes).contains(
                "jobs_claim_idx", "jobs_running_lookup_idx", "jobs_lease_expiry_idx", "outbox_poll_idx");

        // Seed enough rows that the planner prefers indexes over sequential scans.
        // Include sent outbox rows so the partial outbox_poll_idx (sent_at IS NULL)
        // is strictly smaller than the full table and wins over the PK for the poll.
        java.util.List<UUID> jobIds = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            UUID job = createFullJob("t_single", "key-idx-" + i + "-" + UUID.randomUUID(), "QUEUED",
                    String.format("%064x", i + 1000));
            jobIds.add(job);
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb FROM jobs WHERE id = ?",
                    UUID.randomUUID(), job);
        }
        for (UUID jobId : jobIds) {
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb, clock_timestamp() FROM jobs WHERE id = ?",
                    UUID.randomUUID(), jobId);
        }
        // Relay history dominates: mostly-sent table with a small unsent backlog,
        // mirroring production where the partial index is highly selective.
        jdbc.update("INSERT INTO outbox (tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                + "SELECT j.tenant_id, j.id, j.document_id, j.version_id, 'job.committed', '{}'::jsonb, clock_timestamp() "
                + "FROM jobs j CROSS JOIN generate_series(1, 10) g WHERE j.tenant_id = 't_single'");
        jdbc.execute("ANALYZE jobs");
        jdbc.execute("ANALYZE outbox");

        String claimSql = "EXPLAIN SELECT id FROM jobs WHERE tenant_id = 't_single' AND state = 'QUEUED' "
                + "ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED";
        String claimPlan = String.join("\n",
                jdbc.queryForList(claimSql, String.class));
        assertThat(claimPlan).contains("jobs_claim_idx");
        assertThat(claimPlan).doesNotContain("Seq Scan");

        String relaySql = "EXPLAIN SELECT id FROM outbox WHERE sent_at IS NULL "
                + "ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED";
        String relayPlan = String.join("\n",
                jdbc.queryForList(relaySql, String.class));
        assertThat(relayPlan).contains("outbox_poll_idx");
        assertThat(relayPlan).doesNotContain("Seq Scan");
    }
}
