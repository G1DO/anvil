package com.g1do.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claim under a per-tenant quota, in due-time order (issue #35, record 0020).
 *
 * <p>Every test runs against the suite's containerized Postgres. The order is
 * {@code (next_run_at, created_at, id)}: only jobs with {@code next_run_at <=
 * clock_timestamp()} are eligible, and arrival order breaks ties. A tenant
 * holding {@code tenants.max_running} jobs in {@code RUNNING} gets none.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FairClaimTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, "
                + "jobs, document_versions, documents, artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("UPDATE tenants SET max_running = DEFAULT WHERE id = 't_single'");
    }

    // ---- Acceptance 1: quota admission, and order on the database clock ----

    @Test
    void claimAdmitsOnlyTenantsUnderTheirQuota() {
        tenant("t_quota_a", 2);
        List<UUID> a = seedJobs("t_quota_a", 4);

        ClaimedJob a1 = jobs.claim("t_quota_a", "w1").orElseThrow();
        jobs.claim("t_quota_a", "w2").orElseThrow();
        assertThat(jobs.claim("t_quota_a", "w3")).as("third claim at quota 2").isEmpty();
        assertThat(running("t_quota_a")).isEqualTo(2);
        assertThat(attempts("t_quota_a")).as("a refused claim writes no ledger row").isEqualTo(2);

        // A commit frees a slot.
        jobs.issueCap("t_quota_a", a1.jobId());
        assertThat(jobs.commitSucceeded("t_quota_a", a1.jobId(), "w1", a1.fencingToken())).isTrue();
        ClaimedJob a3 = jobs.claim("t_quota_a", "w3").orElseThrow();
        assertThat(a).contains(a3.jobId());

        // An expired lease still holds its slot until the reconciler releases it.
        expireLease(a3.jobId());
        assertThat(jobs.claim("t_quota_a", "w4")).as("expired but not reclaimed still counts").isEmpty();
        assertThat(jobs.reconcile("t_quota_a").releasedToQueued()).isEqualTo(1);
        assertThat(jobs.claim("t_quota_a", "w4")).as("released slot is free").isPresent();

        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET max_running = 0 WHERE id = 't_quota_a'"))
                .as("a quota is at least 1")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentClaimsNeverOvershootTheQuota() throws Exception {
        tenant("t_burst_a", 5);
        seedJobs("t_burst_a", 100);

        List<Optional<ClaimedJob>> direct = race(64, i -> jobs.claim("t_burst_a", "direct-" + i));
        assertThat(direct.stream().filter(Optional::isPresent).count())
                .as("64 simultaneous claims against quota 5").isEqualTo(5);
        assertThat(running("t_burst_a")).isEqualTo(5);
    }

    @Test
    void claimOrdersByNextRunAtOnTheDatabaseClockThenArrival() throws Exception {
        tenant("t_order", 1000);
        UUID[] ids = {
                job("t_order", "-3 seconds", "-1 second", null),
                // Created first, but its next_run_at is later: next_run_at decides.
                job("t_order", "-2 seconds", "-9 seconds", null),
                job("t_order", "-1 second", "-5 seconds", null),
                // Same next_run_at as the one above (pinned below), created later.
                job("t_order", "-1 second", "-4 seconds", null),
        };
        // Same next_run_at and created_at (pinned below): id decides, in Postgres uuid order.
        UUID low = job("t_order", "-1 second", "-3 seconds", UUID.fromString("00000000-0000-4000-8000-000000000001"));
        UUID high = job("t_order", "-1 second", "-3 seconds", UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"));
        // Not yet due on the database clock.
        UUID later = job("t_order", "+2 seconds", "-20 seconds", null);
        UUID future = job("t_order", "+1 hour", "-30 seconds", null);
        // Each insert reads clock_timestamp() on its own, so pin the ties to one instant.
        jdbc.update("UPDATE jobs SET next_run_at = (SELECT next_run_at FROM jobs WHERE id = ?) WHERE id IN (?, ?, ?)",
                ids[2], ids[3], low, high);
        jdbc.update("UPDATE jobs SET created_at = (SELECT created_at FROM jobs WHERE id = ?) WHERE id = ?", low, high);

        assertThat(claimableIds("t_order")).as("the view lists only jobs due on the database clock")
                .containsExactlyInAnyOrder(ids[0], ids[1], ids[2], ids[3], low, high);

        List<UUID> order = new ArrayList<>();
        Optional<ClaimedJob> c;
        while ((c = jobs.claim("t_order", "w")).isPresent()) {
            order.add(c.get().jobId());
        }
        assertThat(order).containsExactly(ids[0], ids[1], ids[2], ids[3], low, high);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT next_run_at <= clock_timestamp() FROM jobs WHERE id = ?", Boolean.class, later))) {
            assertThat(System.nanoTime()).as("database clock reached next_run_at").isLessThan(deadline);
            Thread.sleep(50L);
        }
        assertThat(jobs.claim("t_order", "w").map(ClaimedJob::jobId)).contains(later);
        assertThat(jobs.claim("t_order", "w")).as("a job due in an hour is never claimed early").isEmpty();
        assertThat(state(future)).isEqualTo("QUEUED");
    }

    @Test
    void claimUsesTheClaimOrderIndex() {
        tenant("t_plan", 1000);
        seedJobs("t_plan", 500);
        jdbc.execute("ANALYZE jobs");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id FROM jobs WHERE tenant_id = 't_plan' AND state = 'QUEUED' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp()) "
                        + "AND attempt < max_attempts AND next_run_at <= clock_timestamp() "
                        + "ORDER BY next_run_at, created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED",
                String.class));
        assertThat(plan).contains("jobs_claim_order_idx").doesNotContain("Seq Scan");
    }

    @Test
    void migratingAQueueKeepsItsArrivalOrder() {
        try (PitrDrill drill = new PitrDrill()) {
            drill.start();
            drill.migrate("6");
            JdbcTemplate old = drill.jdbc();
            UUID doc = UUID.randomUUID();
            UUID ver = UUID.randomUUID();
            old.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, 't_single', 'q', 'text/plain')", doc);
            old.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) "
                    + "VALUES (?, 't_single', ?, 1, ?)", ver, doc, "e".repeat(64));
            // Ids run against arrival order, so only created_at can keep it.
            List<UUID> arrival = List.of(
                    UUID.fromString("ffffffff-0000-4000-8000-000000000003"),
                    UUID.fromString("88888888-0000-4000-8000-000000000002"),
                    UUID.fromString("00000000-0000-4000-8000-000000000001"));
            for (int i = 0; i < arrival.size(); i++) {
                old.update("INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, created_at) "
                        + "VALUES (?, 't_single', ?, ?, ?, clock_timestamp() - make_interval(secs => ?))",
                        arrival.get(i), doc, ver, "pre-v7-" + i, 10 - i);
            }

            drill.migrate();
            JdbcTemplate migrated = drill.jdbc();
            assertThat(migrated.queryForObject("SELECT max_running FROM tenants WHERE id = 't_single'", Integer.class))
                    .isEqualTo(JobService.DEFAULT_MAX_RUNNING);
            assertThat(migrated.queryForObject("SELECT count(DISTINCT next_run_at) FROM jobs", Integer.class))
                    .as("existing rows share the migration's time").isEqualTo(1);
            JobService migratedJobs = drill.jobs();
            List<UUID> order = new ArrayList<>();
            Optional<ClaimedJob> c;
            while ((c = migratedJobs.claim("t_single", "w")).isPresent()) {
                order.add(c.get().jobId());
            }
            assertThat(order).containsExactlyElementsOf(arrival);
        }
    }

    // ---- Helpers ----

    @FunctionalInterface
    private interface Claimer<T> {
        T claim(int i) throws Exception;
    }

    /** Releases {@code n} claimers at once and collects their results. */
    private static <T> List<T> race(int n, Claimer<T> claimer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return claimer.claim(index);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void tenant(String id, int maxRunning) {
        jdbc.update("INSERT INTO tenants (id, max_running) VALUES (?, ?)", id, maxRunning);
    }

    private UUID document(String tenant) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, 'fair', 'text/plain')",
                doc, tenant);
        jdbc.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) "
                + "VALUES (?, ?, ?, 1, ?)", doc, tenant, doc, "f".repeat(64));
        return doc;
    }

    /** Queues {@code n} jobs for {@code tenant} in one statement, so they share one arrival time. */
    private List<UUID> seedJobs(String tenant, int n) {
        UUID doc = document(tenant);
        return jdbc.queryForList(
                "INSERT INTO jobs (tenant_id, document_id, version_id, idempotency_key) "
                        + "SELECT ?, ?, ?, 'seed-' || g FROM generate_series(1, ?) g RETURNING id",
                UUID.class, tenant, doc, doc, n);
    }

    /**
     * Queues one job with {@code next_run_at} and {@code created_at} offset from the
     * database clock, e.g. {@code "-3 seconds"}.
     */
    private UUID job(String tenant, String nextRunOffset, String createdOffset, UUID id) {
        UUID doc = document(tenant);
        UUID jobId = id == null ? UUID.randomUUID() : id;
        jdbc.update("INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, next_run_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, clock_timestamp() + ?::interval, clock_timestamp() + ?::interval)",
                jobId, tenant, doc, doc, "job-" + jobId, nextRunOffset, createdOffset);
        return jobId;
    }

    private void expireLease(UUID job) {
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
    }

    private int running(String tenant) {
        return count("SELECT count(*) FROM jobs WHERE tenant_id = ? AND state = 'RUNNING'", tenant);
    }

    private int attempts(String tenant) {
        return count("SELECT count(*) FROM attempts WHERE tenant_id = ?", tenant);
    }

    private int count(String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private Set<UUID> claimableIds(String tenant) {
        return Set.copyOf(jdbc.queryForList("SELECT id FROM claimable_jobs WHERE tenant_id = ?", UUID.class, tenant));
    }

    private String state(UUID job) {
        return jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job);
    }
}
