package com.g1do.anvil;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.TimelineStatus;
import com.g1do.anvil.jobs.TimelineUnconfirmedException;
import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.tenant.TenantGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restore drill for issue #34: backup, clean and point-in-time restore on
 * containerized Postgres ({@link PitrDrill}) to a declared target, with
 * measured recovery time (RTO) and data loss (RPO).
 *
 * <p>Before the target a worker holds job A with a live lease and a grant, and
 * job B has an unsent event. After the target (the window the restore loses)
 * A commits, B's event is published and consumed, a zombie claims B with a
 * grant, and job C is submitted. After the restore, every token and grant
 * from either side of the target must affect 0 rows, the event must apply
 * once, and each job must succeed exactly once.
 *
 * <p>RTO runs from the failure to the first commit on the restored database:
 * restore and WAL replay, the timeline gate waiting for leases, then a
 * re-issued grant and a fresh claim. RPO is the time between the target and
 * the last write before the failure. Both are printed in the report.
 */
class PitrRestoreDrillTest {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void restoreToDeclaredTargetMeasuresRtoRpoAndAcceptsNoRewoundEffect() throws InterruptedException {
        try (PitrDrill drill = new PitrDrill()) {
            drill.start();
            drill.migrate();
            drill.baseBackup();
            JdbcTemplate jdbc = drill.jdbc();
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(drill.dataSource());
            JobService jobs = new JobService(jdbc, txManager);
            OutboxRelay relay = new OutboxRelay(jdbc, txManager);
            IdempotentConsumer consumer = new IdempotentConsumer(jdbc, txManager);
            jdbc.update("INSERT INTO tenants (id) VALUES ('t_other')");

            // Before the target: replayed from WAL on restore.
            UUID jobA = seedQueued(jdbc, "t_single", "a");
            UUID jobB = seedQueued(jdbc, "t_single", "b");
            UUID otherJob = seedQueued(jdbc, "t_other", "o");
            UUID eventB = UUID.randomUUID();
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id) VALUES (?, 't_single', ?)", eventB, jobB);
            ClaimedJob workerA = jobs.claim("t_single", "worker-a").orElseThrow();
            assertThat(workerA.jobId()).isEqualTo(jobA);
            CapGrant capA = jobs.issueCap("t_single", jobA);
            OffsetDateTime target = drill.createRestorePoint();

            // After the target: everything here is lost by the restore.
            List<OutboxEvent> lostDeliveries = new ArrayList<>();
            assertThat(relay.relayOnce((event, headers) -> lostDeliveries.add(event), "t_single")).isEqualTo(1);
            assertThat(consumer.consume(lostDeliveries.get(0))).isTrue();
            assertThat(jobs.commitSucceeded(
                    "t_single", jobA, "worker-a", workerA.fencingToken(), capA.capId(), capA.epoch())).isTrue();
            ClaimedJob zombieB = jobs.claim("t_single", "worker-b").orElseThrow();
            assertThat(zombieB.jobId()).isEqualTo(jobB);
            CapGrant zombieCapB = jobs.issueCap("t_single", jobB);
            UUID jobC = seedQueued(jdbc, "t_single", "c");
            OffsetDateTime lastWrite = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            drill.archiveWal();

            long failedAt = System.nanoTime();
            drill.crash();
            drill.clean();
            drill.restoreToTarget();
            long restoredAt = System.nanoTime();

            // Restored to the declared target, on a new timeline.
            assertThat(jdbc.queryForObject("SELECT ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))"
                    + "::bit(32)::bigint - timeline_id FROM db_timeline", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForList("SELECT state, owner, fencing_token FROM jobs WHERE id = ?", jobA))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("state")).isEqualTo("RUNNING");
                        assertThat(row.get("owner")).isEqualTo("worker-a");
                        assertThat(row.get("fencing_token")).isEqualTo(workerA.fencingToken());
                    });
            assertThat(state(jdbc, jobB)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, jobC)).isZero();
            assertThat(jdbc.queryForObject("SELECT sent_at IS NULL FROM outbox WHERE id = ?", Boolean.class, eventB))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isZero();

            // Until the timeline is confirmed nothing commits or heartbeats:
            // not the restored holder, not the lost-timeline zombie.
            assertThatThrownBy(() -> jobs.commitSucceeded(
                    "t_single", jobA, "worker-a", workerA.fencingToken(), capA.capId(), capA.epoch()))
                    .isInstanceOf(TimelineUnconfirmedException.class);
            assertThatThrownBy(() -> jobs.commitSucceeded(
                    "t_single", jobB, "worker-b", zombieB.fencingToken(), zombieCapB.capId(), zombieCapB.epoch()))
                    .isInstanceOf(TimelineUnconfirmedException.class);
            assertThatThrownBy(() -> jobs.heartbeat("t_single", jobA, "worker-a", workerA.fencingToken()))
                    .isInstanceOf(TimelineUnconfirmedException.class);

            // Confirm the way a polling worker does; it waits out A's lease.
            long deadline = System.nanoTime() + CONFIRM_TIMEOUT.toNanos();
            TimelineStatus status;
            while ((status = jobs.confirmTimeline()) == TimelineStatus.WAITING_FOR_LEASES
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(status).isEqualTo(TimelineStatus.CONFIRMED);
            long confirmedAt = System.nanoTime();
            assertThat(jdbc.queryForObject("SELECT lease_expires_at <= clock_timestamp() FROM jobs WHERE id = ?",
                    Boolean.class, jobA)).isTrue();

            // Grants from both timelines are gone; stale tokens affect 0 rows.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cap_grants", Integer.class)).isZero();
            CapGrant newCapA = jobs.issueCap("t_single", jobA);
            CapGrant newCapB = jobs.issueCap("t_single", jobB);
            assertThatThrownBy(() -> jobs.commitSucceeded(
                    "t_single", jobA, "worker-a", workerA.fencingToken(), capA.capId(), capA.epoch()))
                    .isInstanceOf(CapDeniedException.class);
            assertThatThrownBy(() -> jobs.commitSucceeded(
                    "t_single", jobB, "worker-b", zombieB.fencingToken(), zombieCapB.capId(), zombieCapB.epoch()))
                    .isInstanceOf(CapDeniedException.class);
            assertThat(jobs.heartbeat("t_single", jobA, "worker-a", workerA.fencingToken())).isFalse();
            assertThat(jobs.commitSucceeded(
                    "t_single", jobA, "worker-a", workerA.fencingToken(), newCapA.capId(), newCapA.epoch())).isFalse();
            assertThat(jobs.commitSucceeded(
                    "t_single", jobB, "worker-b", zombieB.fencingToken(), newCapB.capId(), newCapB.epoch())).isFalse();
            assertThat(state(jdbc, jobA)).isEqualTo("RUNNING");
            assertThat(state(jdbc, jobB)).isEqualTo("QUEUED");

            // Work resumes under tokens above any either timeline issued.
            jobs.reconcile("t_single");
            ClaimedJob freshA = jobs.claim("t_single", "worker-fresh").orElseThrow();
            ClaimedJob freshB = jobs.claim("t_single", "worker-fresh").orElseThrow();
            assertThat(List.of(freshA.jobId(), freshB.jobId())).containsExactly(jobA, jobB);
            assertThat(freshA.fencingToken()).isGreaterThan(workerA.fencingToken());
            assertThat(freshB.fencingToken()).isGreaterThan(zombieB.fencingToken());
            assertThat(jobs.commitSucceeded(
                    "t_single", jobA, "worker-fresh", freshA.fencingToken(), newCapA.capId(), newCapA.epoch())).isTrue();
            long firstCommitAt = System.nanoTime();
            assertThat(jobs.commitSucceeded(
                    "t_single", jobB, "worker-fresh", freshB.fencingToken(), newCapB.capId(), newCapB.epoch())).isTrue();

            // The re-published event keeps its identity and applies once.
            List<OutboxEvent> redeliveries = new ArrayList<>();
            assertThat(relay.relayOnce((event, headers) -> redeliveries.add(event), "t_single")).isEqualTo(1);
            assertThat(redeliveries).singleElement().isEqualTo(lostDeliveries.get(0));
            assertThat(consumer.consume(redeliveries.get(0))).isTrue();
            assertThat(consumer.consume(redeliveries.get(0))).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                    Integer.class, eventB)).isEqualTo(1);

            // No double effect: one success and one commit event per job, and
            // the ledger holds no attempt from the lost window.
            for (UUID job : List.of(jobA, jobB)) {
                assertThat(state(jdbc, job)).isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE job_id = ? AND type = 'commit'", Integer.class, job))
                        .isEqualTo(1);
            }
            assertThat(jdbc.queryForList("SELECT owner FROM attempts WHERE job_id = ? ORDER BY attempt_number",
                    String.class, jobA)).containsExactly("worker-a", "worker-fresh");
            assertThat(jdbc.queryForList("SELECT owner FROM attempts WHERE job_id = ? ORDER BY attempt_number",
                    String.class, jobB)).containsExactly("worker-fresh");

            // Tenant isolation and the capability check hold for the runtime role.
            DriverManagerDataSource appSource = new DriverManagerDataSource(drill.jdbcUrl(), "anvil_app", "anvil_app");
            JdbcTemplate appJdbc = new JdbcTemplate(appSource);
            DataSourceTransactionManager appTx = new DataSourceTransactionManager(appSource);
            assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isZero();
            List<UUID> visible = new TransactionTemplate(appTx).execute(tx -> {
                TenantGuard.setTenant(appJdbc, "t_single");
                return appJdbc.queryForList("SELECT id FROM jobs ORDER BY created_at", UUID.class);
            });
            assertThat(visible).containsExactly(jobA, jobB).doesNotContain(otherJob);
            JobService appJobs = new JobService(appJdbc, appTx);
            CapGrant otherCap = appJobs.issueCap("t_other", otherJob);
            ClaimedJob otherClaim = appJobs.claim("t_other", "worker-other").orElseThrow();
            assertThatThrownBy(() -> appJobs.commitSucceeded(
                    "t_single", otherJob, "worker-other", otherClaim.fencingToken(), otherCap.capId(), otherCap.epoch()))
                    .isInstanceOf(CapDeniedException.class);
            assertThat(state(jdbc, otherJob)).isEqualTo("RUNNING");

            Duration rto = Duration.ofNanos(firstCommitAt - failedAt);
            Duration rpo = Duration.between(target, lastWrite);
            assertThat(rto).isPositive();
            assertThat(rpo).isPositive();
            System.out.printf("""
                    PITR restore drill (issue #34)
                      method: pg_basebackup + WAL archive -> pg_ctl stop -m immediate -> rm -rf data -> \
                    restore base backup + archived WAL, promote; postgres:18.6 in one container
                      target: restore point '%s' at %s (database clock)
                      RTO: %d ms = restore and replay %d ms + timeline gate (lease wait) %d ms \
                    + re-issued grant, claim and first commit %d ms
                      RPO: %d ms = last write before the failure (%s) - target
                      lost after the target: job C submit, job A commit, job B claim and grant, event B delivery
                    """,
                    PitrDrill.TARGET, target,
                    rto.toMillis(), Duration.ofNanos(restoredAt - failedAt).toMillis(),
                    Duration.ofNanos(confirmedAt - restoredAt).toMillis(),
                    Duration.ofNanos(firstCommitAt - confirmedAt).toMillis(),
                    rpo.toMillis(), lastWrite);
        }
    }

    /**
     * A database from before V6, restored or not, must not come out of the
     * migration confirmed: its tokens and grants may predate a restore.
     */
    @Test
    void databaseFromBeforeTheGateStaysClosedAfterMigratingToV6() {
        try (PitrDrill drill = new PitrDrill()) {
            drill.start();
            drill.migrate("5");
            JdbcTemplate jdbc = drill.jdbc();
            UUID job = seedQueued(jdbc, "t_single", "pre-v6");
            jdbc.update("UPDATE jobs SET state = 'RUNNING', owner = 'worker-old', fencing_token = 1, attempt = 1, "
                    + "lease_expires_at = clock_timestamp() + INTERVAL '1 hour' WHERE id = ?", job);
            jdbc.update("INSERT INTO cap_grants (tenant_id, job_id, expires_at) "
                    + "VALUES ('t_single', ?, clock_timestamp() + INTERVAL '1 hour')", job);

            drill.migrate();
            JobService jobs = drill.jobs();

            assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-old", 1L))
                    .isInstanceOf(TimelineUnconfirmedException.class);
            assertThatThrownBy(() -> jobs.heartbeat("t_single", job, "worker-old", 1L))
                    .isInstanceOf(TimelineUnconfirmedException.class);
            assertThat(jobs.confirmTimeline()).isEqualTo(TimelineStatus.WAITING_FOR_LEASES);
            assertThat(state(jdbc, job)).isEqualTo("RUNNING");

            jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
            assertThat(jobs.confirmTimeline()).isEqualTo(TimelineStatus.CONFIRMED);
            assertThatThrownBy(() -> jobs.commitSucceeded("t_single", job, "worker-old", 1L))
                    .isInstanceOf(CapDeniedException.class);
            jobs.issueCap("t_single", job);
            assertThat(jobs.commitSucceeded("t_single", job, "worker-old", 1L)).isFalse();
            assertThat(state(jdbc, job)).isEqualTo("RUNNING");
        }
    }

    private static UUID seedQueued(JdbcTemplate jdbc, String tenant, String key) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                doc, tenant, "title-" + key);
        UUID ver = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, tenant, doc, "c".repeat(64));
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, "drill-" + key);
        return job;
    }

    private static String state(JdbcTemplate jdbc, UUID job) {
        return jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job);
    }
}
