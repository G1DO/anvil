package com.g1do.anvil;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.ReconcileResult;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobServiceTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

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

    private UUID createDocument(String tenant) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                id, tenant, "title-" + id.toString().substring(0, 8), "text/plain");
        return id;
    }

    private UUID createVersion(String tenant, UUID documentId, String sha) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, ?, ?)",
                id, tenant, documentId, 1, sha);
        return id;
    }

    private UUID seedQueued(String tenant, String key) {
        UUID doc = createDocument(tenant);
        UUID ver = createVersion(tenant, doc, "a".repeat(63) + "0");
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) "
                        + "VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, key);
        return job;
    }

    private UUID seedTerminal(String tenant, String key, String terminalState) {
        UUID doc = createDocument(tenant);
        UUID ver = createVersion(tenant, doc, "d".repeat(64));
        UUID job = UUID.randomUUID();
        // INSERTs accept any valid state so tests can seed terminals directly.
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                job, tenant, doc, ver, key, terminalState);
        return job;
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", jobId);
    }

    private OffsetDateTime leaseOf(UUID jobId) {
        return jdbc.queryForObject(
                "SELECT lease_expires_at FROM jobs WHERE id = ?", OffsetDateTime.class, jobId);
    }

    // --- Naive helper: select-then-update with no fencing, widened race. ---

    private Optional<UUID> naiveClaim(String tenant, String owner, CountDownLatch selectedBarrier) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM jobs WHERE tenant_id = ? AND state = 'QUEUED' ORDER BY created_at, id LIMIT 1",
                (rs, i) -> (UUID) rs.getObject("id"), tenant);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        if (selectedBarrier != null) {
            selectedBarrier.countDown();
            try {
                selectedBarrier.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } else {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        UUID id = ids.get(0);
        // No fencing: no state/owner/token check, no SKIP LOCKED. Every racer
        // that selected the same row reports success on the same job.
        jdbc.update("UPDATE jobs SET owner = ? WHERE id = ?", owner, id);
        // Re-read state to mimic "claimed" reporting even when another racer
        // already moved it; the point is the same id is reported twice.
        return Optional.of(id);
    }

    @Test
    void naiveSelectThenUpdateExhibitsDoubleClaim() throws Exception {
        seedQueued("t_single", "naive-" + UUID.randomUUID());
        int racers = 10;
        CountDownLatch barrier = new CountDownLatch(racers);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<Optional<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                final String owner = "naive-w" + i;
                futures.add(pool.submit(() -> naiveClaim("t_single", owner, barrier)));
            }
            List<UUID> reported = new ArrayList<>();
            for (Future<Optional<UUID>> f : futures) {
                Optional<UUID> r = f.get(30, TimeUnit.SECONDS);
                r.ifPresent(reported::add);
            }
            // Every racer selected before any update, so every racer reports the
            // same single job: at least one double-claim.
            assertThat(reported).hasSize(racers);
            Set<UUID> distinct = new HashSet<>(reported);
            assertThat(distinct).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void atomicClaimNeverReportsSameJobTwiceUnderContention() throws Exception {
        UUID job = seedQueued("t_single", "atomic-single-" + UUID.randomUUID());
        int claimants = 16;
        ExecutorService pool = Executors.newFixedThreadPool(claimants);
        CountDownLatch ready = new CountDownLatch(claimants);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<ClaimedJob>>> futures = new ArrayList<>();
            for (int i = 0; i < claimants; i++) {
                final String owner = "atomic-w" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return jobs.claim("t_single", owner);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ClaimedJob> claimed = new ArrayList<>();
            for (Future<Optional<ClaimedJob>> f : futures) {
                Optional<ClaimedJob> r = f.get(30, TimeUnit.SECONDS);
                r.ifPresent(claimed::add);
            }
            // Exactly one winner, never the same job twice.
            assertThat(claimed).hasSize(1);
            assertThat(claimed.get(0).jobId()).isEqualTo(job);
            Integer attempts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attempts WHERE job_id = ?", Integer.class, job);
            assertThat(attempts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void atomicClaimAcrossPoolHasZeroDoubleClaims() throws Exception {
        int jobCount = 10;
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            seeded.add(seedQueued("t_single", "pool-" + i + "-" + UUID.randomUUID()));
        }
        int claimants = 32;
        ExecutorService pool = Executors.newFixedThreadPool(claimants);
        CountDownLatch ready = new CountDownLatch(claimants);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<ClaimedJob>>> futures = new ArrayList<>();
            for (int i = 0; i < claimants; i++) {
                final String owner = "pool-w" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return jobs.claim("t_single", owner);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            ConcurrentLinkedQueue<ClaimedJob> claimed = new ConcurrentLinkedQueue<>();
            for (Future<Optional<ClaimedJob>> f : futures) {
                Optional<ClaimedJob> r = f.get(60, TimeUnit.SECONDS);
                r.ifPresent(claimed::add);
            }
            // One claim per job, no duplicates, no lost ledger rows.
            assertThat(claimed).hasSize(jobCount);
            Set<UUID> distinctJobs = new HashSet<>();
            for (ClaimedJob c : claimed) {
                assertThat(distinctJobs.add(c.jobId()))
                        .as("job %s claimed twice", c.jobId())
                        .isTrue();
            }
            assertThat(distinctJobs).containsExactlyInAnyOrderElementsOf(seeded);
            Integer ledger = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attempts WHERE tenant_id = 't_single'", Integer.class);
            assertThat(ledger).isEqualTo(jobCount);
            // UNIQUE(tenant, job, attempt) holds: no doubled attempt numbers.
            Integer distinctAttempts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT DISTINCT tenant_id, job_id, attempt_number FROM attempts) s",
                    Integer.class);
            assertThat(distinctAttempts).isEqualTo(jobCount);
            // Pool exhausted: further claim is empty.
            assertThat(jobs.claim("t_single", "late-worker")).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimIncrementsTokenAndAttemptWithDbClockAndLedger() {
        UUID job = seedQueued("t_single", "inc-" + UUID.randomUUID());
        OffsetDateTime dbBefore = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);

        Optional<ClaimedJob> claimed = jobs.claim("t_single", "w1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().jobId()).isEqualTo(job);
        assertThat(claimed.get().owner()).isEqualTo("w1");
        assertThat(claimed.get().fencingToken()).isEqualTo(1L);
        assertThat(claimed.get().attempt()).isEqualTo(1);

        OffsetDateTime dbAfter = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        Map<String, Object> row = jobRow(job);
        assertThat(row.get("state")).isEqualTo("RUNNING");
        assertThat(row.get("owner")).isEqualTo("w1");
        assertThat(((Number) row.get("fencing_token")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("attempt")).intValue()).isEqualTo(1);

        OffsetDateTime lease = leaseOf(job);
        assertThat(lease).isNotNull();
        // Lease is DB clock + 10s: bounded by the two DB reads plus TTL.
        assertThat(lease).isAfter(dbBefore);
        assertThat(lease).isBefore(dbAfter.plusSeconds(11));
        long skewSeconds = lease.toEpochSecond() - dbAfter.toEpochSecond();
        assertThat(skewSeconds).isBetween(9L, 11L);

        // Ledger: exactly one CLAIMED row for attempt 1 with matching fencing.
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT * FROM attempts WHERE job_id = ?", job);
        assertThat(attempts).hasSize(1);
        assertThat(((Number) attempts.get(0).get("attempt_number")).intValue()).isEqualTo(1);
        assertThat(attempts.get(0).get("owner")).isEqualTo("w1");
        assertThat(((Number) attempts.get(0).get("fencing_token")).longValue()).isEqualTo(1L);
        assertThat(attempts.get(0).get("outcome")).isEqualTo("CLAIMED");
    }

    @Test
    void heartbeatExtendsOnlyLiveLeaseHeldBySameOwnerAndToken() {
        UUID job = seedQueued("t_single", "hb-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        OffsetDateTime lease1 = leaseOf(job);

        // Live heartbeat with matching fencing succeeds and extends.
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken())).isTrue();
        OffsetDateTime lease2 = leaseOf(job);
        assertThat(lease2).isAfter(lease1);

        // Wrong token / wrong owner: zero rows, no state change.
        Map<String, Object> before = jobRow(job);
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken() + 99)).isFalse();
        assertThat(jobs.heartbeat("t_single", job, "intruder", claimed.fencingToken())).isFalse();
        Map<String, Object> after = jobRow(job);
        assertThat(after.get("owner")).isEqualTo(before.get("owner"));
        assertThat(after.get("fencing_token")).isEqualTo(before.get("fencing_token"));
        assertThat(after.get("state")).isEqualTo("RUNNING");
        assertThat(after.get("lease_expires_at")).isEqualTo(before.get("lease_expires_at"));

        // Expired lease: heartbeat fails even with correct fencing.
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                job);
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken())).isFalse();
        assertThat(jobRow(job).get("state")).isEqualTo("RUNNING");
    }

    @Test
    void staleFencingReceivesZeroRowsWithNoStateChange() {
        UUID job = seedQueued("t_single", "stale-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();
        Map<String, Object> before = jobRow(job);

        // Stale token on heartbeat and both commit paths: false, no mutation.
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken() + 1)).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "w1", claimed.fencingToken() + 1)).isFalse();
        assertThat(jobs.commitFailed("t_single", job, "w1", claimed.fencingToken() + 1)).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "intruder", claimed.fencingToken())).isFalse();

        Map<String, Object> after = jobRow(job);
        assertThat(after.get("owner")).isEqualTo(before.get("owner"));
        assertThat(after.get("fencing_token")).isEqualTo(before.get("fencing_token"));
        assertThat(after.get("state")).isEqualTo("RUNNING");
        assertThat(after.get("attempt")).isEqualTo(before.get("attempt"));
        assertThat(after.get("lease_expires_at")).isEqualTo(before.get("lease_expires_at"));
    }

    @Test
    void zombieFenceLoserCommitAffectsZeroRowsLedgerExactlyOnce() {
        UUID job = seedQueued("t_single", "zombie-" + UUID.randomUUID());

        // Worker A holds the job.
        ClaimedJob a = jobs.claim("t_single", "worker-A").orElseThrow();
        assertThat(a.fencingToken()).isEqualTo(1L);
        assertThat(a.attempt()).isEqualTo(1);

        // Suspended past lease expiry (40s SIGSTOP in the issue; here the lease
        // is expired directly via the DB clock so expiry is guaranteed and the
        // test stays fast). Reconciler polling (1s in production) releases it.
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                job);
        ReconcileResult released = jobs.reconcile("t_single");
        assertThat(released.releasedToQueued()).isEqualTo(1);
        Map<String, Object> releasedRow = jobRow(job);
        assertThat(releasedRow.get("state")).isEqualTo("QUEUED");

        // Worker B claims with token+1.
        ClaimedJob b = jobs.claim("t_single", "worker-B").orElseThrow();
        assertThat(b.jobId()).isEqualTo(job);
        assertThat(b.fencingToken()).isEqualTo(a.fencingToken() + 1);
        assertThat(b.attempt()).isEqualTo(a.attempt() + 1);

        // A resumes: stale heartbeat and commit affect zero rows.
        assertThat(jobs.heartbeat("t_single", job, "worker-A", a.fencingToken())).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "worker-A", a.fencingToken())).isFalse();
        Map<String, Object> stillRunning = jobRow(job);
        assertThat(stillRunning.get("state")).isEqualTo("RUNNING");
        assertThat(stillRunning.get("owner")).isEqualTo("worker-B");

        // B commits: exactly one completion.
        assertThat(jobs.commitSucceeded("t_single", job, "worker-B", b.fencingToken())).isTrue();
        assertThat(jobRow(job).get("state")).isEqualTo("SUCCEEDED");

        // Ledger: both claims recorded, zero lost, zero doubled.
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT attempt_number, owner, fencing_token FROM attempts WHERE job_id = ? ORDER BY attempt_number",
                job);
        assertThat(ledger).hasSize(2);
        assertThat(((Number) ledger.get(0).get("attempt_number")).intValue()).isEqualTo(1);
        assertThat(ledger.get(0).get("owner")).isEqualTo("worker-A");
        assertThat(((Number) ledger.get(1).get("attempt_number")).intValue()).isEqualTo(2);
        assertThat(ledger.get(1).get("owner")).isEqualTo("worker-B");
    }

    @Test
    void reconcilerReleasesAbandonedAndDeadensExhausted() {
        // Abandoned: RUNNING expired with attempts remaining.
        UUID abandoned = seedQueued("t_single", "rec-ab-" + UUID.randomUUID());
        ClaimedJob ab = jobs.claim("t_single", "w-old").orElseThrow();
        assertThat(ab.jobId()).isEqualTo(abandoned);
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                abandoned);

        // Exhausted queued: attempt >= max_attempts.
        UUID exhaustedQueued = seedQueued("t_single", "rec-eq-" + UUID.randomUUID());
        jdbc.update("UPDATE jobs SET attempt = max_attempts WHERE id = ?", exhaustedQueued);

        // Exhausted running expired.
        UUID exhaustedRunning = seedQueued("t_single", "rec-er-" + UUID.randomUUID());
        ClaimedJob er = jobs.claim("t_single", "w-er").orElseThrow();
        assertThat(er.jobId()).isEqualTo(exhaustedRunning);
        jdbc.update(
                "UPDATE jobs SET attempt = max_attempts, lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                exhaustedRunning);

        // Controls: healthy running with live lease first (pool is otherwise
        // empty, so this claim deterministically takes healthyRunning), then a
        // healthy queued row that stays claimable.
        UUID healthyRunning = seedQueued("t_single", "rec-hr-" + UUID.randomUUID());
        ClaimedJob hr = jobs.claim("t_single", "w-live").orElseThrow();
        assertThat(hr.jobId()).isEqualTo(healthyRunning);
        UUID healthyQueued = seedQueued("t_single", "rec-hq-" + UUID.randomUUID());

        ReconcileResult result = jobs.reconcile("t_single");
        assertThat(result.releasedToQueued()).isEqualTo(1);
        assertThat(result.markedDead()).isEqualTo(2);

        assertThat(jobRow(abandoned).get("state")).isEqualTo("QUEUED");
        assertThat(jobRow(abandoned).get("owner")).isNull();
        assertThat(jobRow(abandoned).get("lease_expires_at")).isNull();

        assertThat(jobRow(exhaustedQueued).get("state")).isEqualTo("DEAD");
        assertThat(jobRow(exhaustedRunning).get("state")).isEqualTo("DEAD");

        assertThat(jobRow(healthyQueued).get("state")).isEqualTo("QUEUED");
        assertThat(jobRow(healthyRunning).get("state")).isEqualTo("RUNNING");

        // Abandoned is claimable again; exhausted are never re-offered.
        List<UUID> claimable = jdbc.query(
                "SELECT id FROM claimable_jobs WHERE tenant_id = 't_single'",
                (rs, i) -> (UUID) rs.getObject("id"));
        assertThat(claimable).contains(abandoned, healthyQueued);
        assertThat(claimable).doesNotContain(exhaustedQueued, exhaustedRunning, healthyRunning);

        Optional<ClaimedJob> reclaimed = jobs.claim("t_single", "w-new");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().jobId()).isIn(abandoned, healthyQueued);
    }

    @Test
    void terminalJobsNeverOfferedAsRunnable() {
        UUID succeeded = seedTerminal("t_single", "term-s-" + UUID.randomUUID(), "SUCCEEDED");
        UUID failed = seedTerminal("t_single", "term-f-" + UUID.randomUUID(), "FAILED");
        UUID dead = seedTerminal("t_single", "term-d-" + UUID.randomUUID(), "DEAD");
        UUID queued = seedQueued("t_single", "term-q-" + UUID.randomUUID());

        ClaimedJob first = jobs.claim("t_single", "w1").orElseThrow();
        assertThat(first.jobId()).isEqualTo(queued);
        assertThat(jobs.claim("t_single", "w2")).isEmpty();

        List<UUID> claimable = jdbc.query(
                "SELECT id FROM claimable_jobs WHERE tenant_id = 't_single'",
                (rs, i) -> (UUID) rs.getObject("id"));
        assertThat(claimable).doesNotContain(succeeded, failed, dead);
    }

    @Test
    void exhaustedJobMovesToDeadRatherThanReoffered() {
        UUID job = seedQueued("t_single", "exh-" + UUID.randomUUID());
        jdbc.update("UPDATE jobs SET attempt = max_attempts WHERE id = ?", job);

        // Not claimable at or beyond max attempts.
        assertThat(jobs.claim("t_single", "w1")).isEmpty();

        ReconcileResult result = jobs.reconcile("t_single");
        assertThat(result.markedDead()).isEqualTo(1);
        assertThat(jobRow(job).get("state")).isEqualTo("DEAD");
        assertThat(jobs.claim("t_single", "w2")).isEmpty();
    }

    @Test
    void commitTransitionsAreFencedAndTerminal() {
        UUID job = seedQueued("t_single", "commit-" + UUID.randomUUID());
        ClaimedJob claimed = jobs.claim("t_single", "w1").orElseThrow();

        assertThat(jobs.commitSucceeded("t_single", job, "w1", claimed.fencingToken())).isTrue();
        assertThat(jobRow(job).get("state")).isEqualTo("SUCCEEDED");

        // Terminal: further heartbeat/commit affect zero rows (trigger also
        // rejects any terminal mutation at the database).
        assertThat(jobs.heartbeat("t_single", job, "w1", claimed.fencingToken())).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "w1", claimed.fencingToken())).isFalse();

        UUID job2 = seedQueued("t_single", "commit-f-" + UUID.randomUUID());
        ClaimedJob claimed2 = jobs.claim("t_single", "w1").orElseThrow();
        assertThat(claimed2.jobId()).isEqualTo(job2);
        assertThat(jobs.commitFailed("t_single", job2, "w1", claimed2.fencingToken())).isTrue();
        assertThat(jobRow(job2).get("state")).isEqualTo("FAILED");
    }

    @Test
    void claimTimingsMatchIssueConstraints() {
        assertThat(JobService.LEASE_TTL_SECONDS).isEqualTo(10);
        assertThat(JobService.HEARTBEAT_INTERVAL_SECONDS).isEqualTo(3);
        assertThat(JobService.CLAIM_LIMIT).isEqualTo(20);
        assertThat(JobService.MAX_ATTEMPTS).isEqualTo(5);
        assertThat(JobService.RECONCILER_POLL_SECONDS).isEqualTo(1);
    }

    @Test
    void canonicalCandidateSelectionUsesClaimIndex() {
        // The production claim filters the same shape the V1 migration documents
        // as canonical; that shape must keep using jobs_claim_idx once the
        // table is large enough for the planner to prefer the index (mirrors
        // DurableV1SchemaTest seeding).
        for (int i = 0; i < 200; i++) {
            seedQueued("t_single", "explain-" + i + "-" + UUID.randomUUID());
        }
        jdbc.execute("ANALYZE jobs");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id FROM jobs WHERE tenant_id = 't_single' AND state = 'QUEUED' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp()) "
                        + "AND attempt < max_attempts ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED",
                String.class));
        assertThat(plan).contains("jobs_claim_idx");
        assertThat(plan).doesNotContain("Seq Scan");
    }
}
