package com.g1do.anvil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.TimelineStatus;
import com.g1do.anvil.jobs.TimelineUnconfirmedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Timeline check for issue #34: the INV-1 rewind repro plus the fail-closed
 * gate (record 0016). The real backup, clean and restore drill is
 * {@link PitrRestoreDrillTest}.
 *
 * <p>INV-1 repro (record 0013):
 * <p>Containerized PG only (Testcontainers {@code postgres:18}); no prod
 * restore, no base backup, no WAL replay. Simulates a PITR timeline rewind
 * by resetting the {@code jobs} row to its pre-claim state (QUEUED, prior
 * token, null lease) and removing the rewound claim attempt + cap grant.
 * Real PITR would also rewind {@code audit_events}/{@code job_checkpoints}
 * (append-only, TRUNCATE-only cleanup) and bump the PG timeline ID; that
 * fidelity gap is documented in
 * {@code docs/decisions/0013-pitr-timeline-check.md}.
 *
 * <p>Proves: stale owner/token affects 0 rows post-rewind, no double effect,
 * and cap must be re-issued (missing cap denies 403 NEEDS_APPROVAL).
 *
 * <p>Gate: a rewound timeline (the stored {@code db_timeline} row older than
 * the server's, as after promotion) or an unknown one refuses claim,
 * heartbeat and commit until leases expire and capabilities are re-issued.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PitrTimelineCheckTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE extraction_dead_letters, extracted_facts, extractions, job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
    }

    /** Leaves the gate open for the next test whatever this one did to it. */
    @AfterEach
    void reconfirm() {
        jdbc.update("UPDATE db_timeline SET system_identifier = s.system_identifier, "
                + "timeline_id = ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::bigint "
                + "FROM pg_control_system() s WHERE id = 1");
    }

    private UUID seedQueued(String key) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                doc, "t_single", "title-" + key);
        UUID ver = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, "t_single", doc, "b".repeat(64));
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, "t_single", doc, ver, key);
        return job;
    }

    @Test
    void rewoundEpochStaleOwnerAffectsZeroRowsWithNoDoubleEffect() {
        UUID job = seedQueued("pitr-rewind-" + UUID.randomUUID());
        ClaimedJob stale = jobs.claim("t_single", "worker-stale").orElseThrow();
        long staleToken = stale.fencingToken();
        CapGrant cap = jobs.issueCap("t_single", job);
        assertThat(cap.epoch()).isEqualTo(1L);

        // Simulate PITR rewind to before the claim: jobs row back to QUEUED
        // with prior token, lease cleared; rewound attempt + cap removed.
        // Gap vs real PITR: audit_events/job_checkpoints are append-only and
        // are NOT rewound here (TRUNCATE-only cleanup); PG timeline ID is not
        // bumped. See decision 0013.
        jdbc.update("UPDATE jobs SET state = 'QUEUED', owner = NULL, fencing_token = ?, lease_expires_at = NULL WHERE id = ?",
                staleToken - 1, job);
        jdbc.update("DELETE FROM attempts WHERE tenant_id = 't_single' AND job_id = ? AND attempt_number = ?",
                job, stale.attempt());
        jdbc.update("DELETE FROM cap_grants WHERE tenant_id = 't_single' AND job_id = ?", job);

        // Stale owner/token is now from a rewound epoch: heartbeat must affect
        // 0 rows (false). Commit must not apply: cap gate denies first (no
        // grant post-rewind) with 403 NEEDS_APPROVAL, proving no double effect.
        assertThat(jobs.heartbeat("t_single", job, "worker-stale", staleToken)).isFalse();
        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-stale", staleToken))
                .isInstanceOf(CapDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job))
                .isEqualTo("QUEUED");

        // Fail-closed: no valid cap exists post-rewind, so a fresh claim still
        // cannot commit until the cap is re-issued.
        ClaimedJob fresh = jobs.claim("t_single", "worker-fresh").orElseThrow();
        long rejectedBefore = jobs.capRejectedCount();
        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-fresh", fresh.fencingToken()))
                .isInstanceOf(CapDeniedException.class);
        assertThat(jobs.capRejectedCount()).isEqualTo(rejectedBefore + 1);

        // Re-issue cap on the new timeline epoch, then commit once.
        jobs.issueCap("t_single", job);
        assertThat(jobs.commitSucceeded("t_single", job, "worker-fresh", fresh.fencingToken())).isTrue();

        // Exactly-once: one SUCCEEDED row, stale token never applied.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ? AND state = 'SUCCEEDED'",
                Integer.class, job)).isEqualTo(1);
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT attempt_number, owner, fencing_token FROM attempts WHERE job_id = ? ORDER BY attempt_number", job);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).get("owner")).isEqualTo("worker-fresh");
        assertThat(ledger.get(0).get("fencing_token")).isEqualTo(fresh.fencingToken());
    }

    @Test
    void rewoundTimelineRefusesUntilLeasesExpireAndCapsAreReissued() {
        UUID job = seedQueued("pitr-gate-" + UUID.randomUUID());
        UUID other = seedQueued("pitr-gate-other-" + UUID.randomUUID());
        ClaimedJob held = jobs.claim("t_single", "worker-held").orElseThrow();
        assertThat(held.jobId()).isEqualTo(job);
        CapGrant cap = jobs.issueCap("t_single", job);

        // A restore promotes onto a new timeline; the restored row still
        // names the old one.
        jdbc.update("UPDATE db_timeline SET timeline_id = timeline_id - 1 WHERE id = 1");

        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-held", held.fencingToken()))
                .isInstanceOf(TimelineUnconfirmedException.class)
                .satisfies(e -> assertThat(((TimelineUnconfirmedException) e).getStatusCode().value())
                        .isEqualTo(503));
        assertThatThrownBy(() -> jobs.commitSucceeded(
                "t_single", job, "worker-held", held.fencingToken(), cap.capId(), cap.epoch()))
                .isInstanceOf(TimelineUnconfirmedException.class);
        assertThatThrownBy(() -> jobs.heartbeat("t_single", job, "worker-held", held.fencingToken()))
                .isInstanceOf(TimelineUnconfirmedException.class);
        // Claim tries to confirm, but the held lease is still live.
        assertThatThrownBy(() -> jobs.claim("t_single", "worker-fresh"))
                .isInstanceOf(TimelineUnconfirmedException.class);
        assertThat(jobs.confirmTimeline()).isEqualTo(TimelineStatus.WAITING_FOR_LEASES);

        // Refusals and the waiting confirm changed nothing.
        assertThat(state(job)).isEqualTo("RUNNING");
        assertThat(state(other)).isEqualTo("QUEUED");
        assertThat(token(job)).isEqualTo(held.fencingToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cap_grants WHERE job_id = ?", Integer.class, job))
                .isEqualTo(1);
        assertThat(commitEvents(job)).isZero();

        // The lease expires on the database clock; confirm reopens the gate.
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
        assertThat(jobs.confirmTimeline()).isEqualTo(TimelineStatus.CONFIRMED);
        assertThat(token(job)).isEqualTo(held.fencingToken() + JobService.MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cap_grants", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE job_id = ? AND type = 'cap.revoke'", Integer.class, job))
                .isEqualTo(1);

        // Every commit waits for a re-issued capability, and the old grant
        // never matches again.
        assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-held", held.fencingToken()))
                .isInstanceOf(CapDeniedException.class);
        CapGrant reissued = jobs.issueCap("t_single", job);
        assertThat(reissued.capId()).isNotEqualTo(cap.capId());
        assertThatThrownBy(() -> jobs.commitSucceeded(
                "t_single", job, "worker-held", held.fencingToken(), cap.capId(), cap.epoch()))
                .isInstanceOf(CapDeniedException.class);

        // The pre-restore token now affects 0 rows.
        assertThat(jobs.heartbeat("t_single", job, "worker-held", held.fencingToken())).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "worker-held", held.fencingToken())).isFalse();
        assertThat(state(job)).isEqualTo("RUNNING");

        // The job runs again under a token above any the lost timeline could
        // have issued (held + max_attempts - attempt), and commits once.
        jobs.reconcile("t_single");
        ClaimedJob fresh = jobs.claim("t_single", "worker-fresh").orElseThrow();
        assertThat(fresh.jobId()).isEqualTo(job);
        assertThat(fresh.fencingToken())
                .isGreaterThan(held.fencingToken() + JobService.MAX_ATTEMPTS - held.attempt());
        assertThat(jobs.commitSucceeded("t_single", job, "worker-fresh", fresh.fencingToken())).isTrue();
        assertThat(state(job)).isEqualTo("SUCCEEDED");
        assertThat(commitEvents(job)).isEqualTo(1);
    }

    @Test
    void unknownTimelineRefusesCommitsAndCannotBeConfirmed() {
        UUID job = seedQueued("pitr-unknown-" + UUID.randomUUID());
        ClaimedJob held = jobs.claim("t_single", "worker-held").orElseThrow();
        jobs.issueCap("t_single", job);

        try (HikariDataSource appPool = newAppPool()) {
            JobService appJobs = new JobService(new JdbcTemplate(appPool), new DataSourceTransactionManager(appPool));
            // A hardened server that hides pg_control from the runtime role:
            // the timeline can't be read, so the gate stays closed. The test
            // starts from PUBLIC holding EXECUTE with no grant to anvil_app,
            // and the finally block puts that back.
            assertThat(jdbc.queryForObject("""
                    SELECT proacl IS NULL OR (
                      EXISTS (SELECT 1 FROM aclexplode(proacl) a
                              WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE')
                      AND NOT EXISTS (SELECT 1 FROM aclexplode(proacl) a
                                      WHERE a.grantee = 'anvil_app'::regrole))
                    FROM pg_proc WHERE oid = 'pg_control_system()'::regprocedure
                    """, Boolean.class))
                    .as("PUBLIC executes pg_control_system() and anvil_app has no grant of its own")
                    .isTrue();
            jdbc.execute("REVOKE EXECUTE ON FUNCTION pg_control_system() FROM PUBLIC");
            try {
                assertThatThrownBy(() -> appJobs.commitSucceeded("t_single", job, "worker-held", held.fencingToken()))
                        .isInstanceOf(TimelineUnconfirmedException.class);
                assertThat(appJobs.confirmTimeline()).isEqualTo(TimelineStatus.UNKNOWN);
                assertThatThrownBy(() -> appJobs.claim("t_single", "worker-app"))
                        .isInstanceOf(TimelineUnconfirmedException.class);
                assertThat(state(job)).isEqualTo("RUNNING");
                assertThat(commitEvents(job)).isZero();
            } finally {
                // The default ACL lets PUBLIC execute, so this restores the
                // baseline privileges asserted above.
                jdbc.execute("GRANT EXECUTE ON FUNCTION pg_control_system() TO PUBLIC");
            }
            // Same role and job once the timeline is readable again.
            assertThat(appJobs.commitSucceeded("t_single", job, "worker-held", held.fencingToken())).isTrue();
        }
    }

    private String state(UUID job) {
        return jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job);
    }

    private long token(UUID job) {
        return jdbc.queryForObject("SELECT fencing_token FROM jobs WHERE id = ?", Long.class, job);
    }

    private int commitEvents(UUID job) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE job_id = ? AND type = 'commit'", Integer.class, job);
    }

    private HikariDataSource newAppPool() {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getJdbcUrl() == null) {
            throw new IllegalStateException("Hikari DataSource with JDBC URL required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(hikari.getJdbcUrl());
        config.setUsername("anvil_app");
        config.setPassword("anvil_app");
        config.setMaximumPoolSize(2);
        config.setPoolName("pitr-app-pool-" + UUID.randomUUID());
        return new HikariDataSource(config);
    }
}
