package com.g1do.anvil;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.ReconcileResult;
import com.g1do.anvil.jobs.TenantClaim;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fair claim across tenants under a per-tenant quota (issue #35, record 0020).
 *
 * <p>Every test runs against the suite's containerized Postgres. The order is
 * {@code (penalty, next_run_at, created_at, id)}: the penalty is the tenant's
 * {@code RUNNING} count, only jobs with {@code next_run_at <=
 * clock_timestamp()} are eligible, and arrival order breaks ties. A tenant
 * holding {@code tenants.max_running} jobs in {@code RUNNING} gets none.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FairClaimTest {

    /** {@code tenants.max_running} default from V7. */
    private static final int V7_DEFAULT_MAX_RUNNING = 1000;

    /** Worker threads in the flood runs. */
    private static final int POOL = 8;

    /** Jobs the flooding tenant queues up front. */
    private static final int FLOOD_JOBS = 800;

    /** How long a worker holds each job, so the flood fills the pool. */
    private static final long HOLD_MILLIS = 40L;

    /** Jobs the quiet tenant submits one at a time while the flood runs. */
    private static final int QUIET_JOBS = 20;

    /**
     * Most flood claims allowed between a quiet job's submit and its claim.
     * Workers that ranked tenants before the submit committed may still take
     * a flood job each, so a fair claim stays under one round of the pool;
     * twice the pool leaves slack. Without the penalty the quiet job waits
     * for the whole flood.
     */
    private static final int MAX_FLOOD_CLAIMS_AHEAD = 2 * POOL;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @Autowired
    DataSource dataSource;

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
        tenant("t_quota_b", 5);
        List<UUID> a = seedJobs("t_quota_a", 4);
        List<UUID> b = seedJobs("t_quota_b", 4);

        ClaimedJob a1 = jobs.claim("t_quota_a", "w1").orElseThrow();
        jobs.claim("t_quota_a", "w2").orElseThrow();
        assertThat(jobs.claim("t_quota_a", "w3")).as("third claim at quota 2").isEmpty();
        assertThat(running("t_quota_a")).isEqualTo(2);
        assertThat(attempts("t_quota_a")).as("a refused claim writes no ledger row").isEqualTo(2);

        // t_quota_b runs 3 (penalty 3 > 2), so without the quota t_quota_a would rank first.
        for (int i = 0; i < 3; i++) {
            jobs.claim("t_quota_b", "wb" + i).orElseThrow();
        }
        TenantClaim next = jobs.claimNext("w-next").orElseThrow();
        assertThat(next.tenantId()).as("claimNext skips the tenant at its quota").isEqualTo("t_quota_b");
        assertThat(b).contains(next.job().jobId());

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
        tenant("t_burst_b", 5);
        seedJobs("t_burst_a", 100);
        seedJobs("t_burst_b", 100);

        List<Optional<ClaimedJob>> direct = race(64, i -> jobs.claim("t_burst_a", "direct-" + i));
        assertThat(direct.stream().filter(Optional::isPresent).count())
                .as("64 simultaneous claims against quota 5").isEqualTo(5);
        assertThat(running("t_burst_a")).isEqualTo(5);

        jdbc.update("UPDATE jobs SET state = 'QUEUED', owner = NULL, lease_expires_at = NULL "
                + "WHERE tenant_id = 't_burst_a' AND state = 'RUNNING'");
        List<Optional<TenantClaim>> fair = race(64, i -> jobs.claimNext("next-" + i));
        Map<String, Integer> perTenant = new HashMap<>();
        fair.stream().flatMap(Optional::stream).forEach(c -> perTenant.merge(c.tenantId(), 1, Integer::sum));
        assertThat(perTenant).as("64 simultaneous claimNext against two quotas of 5")
                .containsExactlyInAnyOrderEntriesOf(Map.of("t_burst_a", 5, "t_burst_b", 5));
        assertThat(running("t_burst_a")).isEqualTo(5);
        assertThat(running("t_burst_b")).isEqualTo(5);
    }

    @Test
    void quotaHoldsUnderRepeatableReadConnections() throws Exception {
        // A pool whose connections default to repeatable read takes each claim's snapshot
        // before the tenant lock unless the claim sets its own isolation level.
        tenant("t_rr", 5);
        seedJobs("t_rr", 100);
        HikariDataSource main = (HikariDataSource) dataSource;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(main.getJdbcUrl());
        config.setUsername(main.getUsername());
        config.setPassword(main.getPassword());
        config.setMaximumPoolSize(16);
        config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        config.setPoolName("fair-claim-repeatable-read");
        try (HikariDataSource rr = new HikariDataSource(config)) {
            JobService rrJobs = new JobService(new JdbcTemplate(rr), new DataSourceTransactionManager(rr));
            List<Optional<ClaimedJob>> claims = race(64, i -> rrJobs.claim("t_rr", "rr-" + i));
            assertThat(claims.stream().filter(Optional::isPresent).count())
                    .as("64 simultaneous claims against quota 5 on repeatable-read connections").isEqualTo(5);
            assertThat(running("t_rr")).isEqualTo(5);
        }
    }

    @Test
    void claimNextMovesPastATenantWhoseClaimLockIsBusy() throws Exception {
        tenant("t_busy_a", 1000);
        tenant("t_busy_b", 1000);
        job("t_busy_a", "-10 seconds", "-10 seconds", null);
        UUID b = job("t_busy_b", "-1 second", "-1 second", null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // Another claim holds t_busy_a's lock (same key as JobService's claim lock).
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(hashtextextended('anvil.claim:' || 't_busy_a', 0))");
            }
            Future<Optional<TenantClaim>> next = pool.submit(() -> jobs.claimNext("w"));
            TenantClaim claimed = next.get(10, TimeUnit.SECONDS).orElseThrow();
            assertThat(claimed.tenantId()).as("the ranked-first tenant is busy, so the next one is claimed")
                    .isEqualTo("t_busy_b");
            assertThat(claimed.job().jobId()).isEqualTo(b);
            holder.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jobs.claimNext("w").map(TenantClaim::tenantId)).as("free again").contains("t_busy_a");
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
        assertThat(jdbc.queryForObject("SELECT next_run_at > clock_timestamp() FROM active_jobs WHERE id = ?",
                Boolean.class, future)).as("active_jobs shows the due time").isTrue();

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
    void claimNextRanksTenantsByPenaltyThenNextRunAtThenArrival() {
        tenant("t_rank_a", 1000);
        tenant("t_rank_b", 1000);
        tenant("t_rank_c", 1000);
        // t_rank_a already runs 2 jobs and has the oldest queued one.
        job("t_rank_a", "-30 seconds", "-30 seconds", null);
        job("t_rank_a", "-29 seconds", "-29 seconds", null);
        jobs.claim("t_rank_a", "pre").orElseThrow();
        jobs.claim("t_rank_a", "pre").orElseThrow();
        UUID a1 = job("t_rank_a", "-10 seconds", "-10 seconds", null);
        UUID b1 = job("t_rank_b", "-5 seconds", "-5 seconds", null);
        UUID b2 = job("t_rank_b", "-4 seconds", "-4 seconds", null);
        UUID c1 = job("t_rank_c", "-3 seconds", "-3 seconds", null);

        List<String> claimed = new ArrayList<>();
        Optional<TenantClaim> next;
        while ((next = jobs.claimNext("w")).isPresent()) {
            claimed.add(next.get().tenantId() + ":" + next.get().job().jobId());
        }
        // Penalties start at a=2, b=0, c=0. b1 beats c1 on next_run_at; then c (0) beats b (1);
        // then b (1) beats a (2); a's older job goes last.
        assertThat(claimed).containsExactly(
                "t_rank_b:" + b1, "t_rank_c:" + c1, "t_rank_b:" + b2, "t_rank_a:" + a1);
    }

    @Test
    void claimNextBreaksTiesByArrivalThenPostgresUuidOrder() {
        tenant("t_tie_d", 1000);
        tenant("t_tie_e", 1000);
        tenant("t_tie_f", 1000);
        // Java's UUID.compareTo puts ffff... first (signed); Postgres puts 0000... first.
        UUID high = job("t_tie_d", "-1 second", "-2 seconds", UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"));
        UUID low = job("t_tie_e", "-1 second", "-2 seconds", UUID.fromString("00000000-0000-4000-8000-000000000001"));
        UUID older = job("t_tie_f", "-1 second", "-3 seconds", null);
        jdbc.update("UPDATE jobs SET next_run_at = (SELECT next_run_at FROM jobs WHERE id = ?) WHERE id IN (?, ?)",
                high, low, older);
        jdbc.update("UPDATE jobs SET created_at = (SELECT created_at FROM jobs WHERE id = ?) WHERE id = ?", high, low);

        List<UUID> order = new ArrayList<>();
        Optional<TenantClaim> next;
        while ((next = jobs.claimNext("w")).isPresent()) {
            order.add(next.get().job().jobId());
        }
        assertThat(order).containsExactly(older, low, high);
    }

    @Test
    void claimUsesTheClaimOrderIndex() {
        tenant("t_plan", 1000);
        seedJobs("t_plan", 500);
        jdbc.execute("ANALYZE jobs");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id FROM jobs WHERE tenant_id = 't_plan' AND state = 'QUEUED' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp()) "
                        + "AND attempt < max_attempts AND next_run_at <= now() "
                        + "ORDER BY next_run_at, created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED",
                String.class));
        assertThat(plan).contains("jobs_claim_order_idx").doesNotContain("Seq Scan").doesNotContain("Sort");
        assertThat(plan).as("the due time bounds the index scan instead of filtering after it")
                .containsPattern("Index Cond: .*next_run_at <= now\\(\\)");
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
                    .isEqualTo(V7_DEFAULT_MAX_RUNNING);
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

    // ---- Acceptance 2: a flood can't starve a quiet tenant or exceed its quota ----

    @Test
    void floodUnderItsQuotaLeavesWorkersForAQuietTenant() throws Exception {
        FloodRun run = flood(3);
        assertThat(run.maxRunning().get("t_flood")).as("flood reached its quota and never passed it").isEqualTo(3);
        run.assertQuietTenantProgressed();
    }

    @Test
    void floodAboveThePoolStillLetsAQuietTenantThrough() throws Exception {
        // The quota doesn't bind here (1000 > 8 workers), so the penalty alone keeps the quiet tenant moving.
        FloodRun run = flood(V7_DEFAULT_MAX_RUNNING);
        assertThat(run.maxRunning().get("t_flood")).as("flood took most of the pool").isGreaterThan(POOL / 2);
        run.assertQuietTenantProgressed();
    }

    // ---- Acceptance 3: fencing, leases, reconciler, terminals and capabilities on claimNext ----

    @Test
    void claimNextKeepsFencingLeasesReconcilerTerminalsAndCapabilities() {
        tenant("t_fence", 1000);
        UUID job = job("t_fence", "-1 second", "-1 second", null);
        jdbc.update("UPDATE jobs SET max_attempts = 2 WHERE id = ?", job);
        UUID succeeded = terminal("t_fence", "SUCCEEDED");
        UUID dead = terminal("t_fence", "DEAD");
        UUID exhausted = job("t_fence", "-2 seconds", "-2 seconds", null);
        jdbc.update("UPDATE jobs SET attempt = max_attempts WHERE id = ?", exhausted);

        TenantClaim a = jobs.claimNext("worker-A").orElseThrow();
        assertThat(a.tenantId()).isEqualTo("t_fence");
        assertThat(a.job().jobId()).as("exhausted and terminal jobs are never offered").isEqualTo(job);
        assertThat(a.job().attempt()).isEqualTo(1);
        jobs.issueCap("t_fence", job);
        assertThat(jobs.heartbeat("t_fence", job, "worker-A", a.job().fencingToken())).isTrue();

        expireLease(job);
        assertThat(jobs.reconcile("t_fence")).isEqualTo(new ReconcileResult(1, 1));
        assertThat(state(exhausted)).as("the reconciler exhausts a queued job").isEqualTo("DEAD");

        TenantClaim b = jobs.claimNext("worker-B").orElseThrow();
        assertThat(b.job().jobId()).isEqualTo(job);
        assertThat(b.job().fencingToken()).isEqualTo(a.job().fencingToken() + 1);
        assertThat(b.job().attempt()).isEqualTo(2);
        assertThat(jobs.heartbeat("t_fence", job, "worker-A", a.job().fencingToken()))
                .as("stale heartbeat").isFalse();
        assertThat(jobs.commitSucceeded("t_fence", job, "worker-A", a.job().fencingToken()))
                .as("stale commit affects 0 rows").isFalse();

        jobs.revokeCap("t_fence", job);
        long rejected = jobs.capRejectedCount();
        assertThatThrownBy(() -> jobs.commitSucceeded("t_fence", job, "worker-B", b.job().fencingToken()))
                .isInstanceOf(CapDeniedException.class);
        assertThat(jobs.capRejectedCount()).isEqualTo(rejected + 1);
        assertThat(state(job)).isEqualTo("RUNNING");
        jobs.issueCap("t_fence", job);
        assertThat(jobs.commitSucceeded("t_fence", job, "worker-B", b.job().fencingToken())).isTrue();

        assertThat(jobs.claimNext("worker-C")).as("nothing left to offer").isEmpty();
        for (UUID terminal : List.of(job, succeeded, dead, exhausted)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET owner = 'x' WHERE id = ?", terminal))
                    .as("terminal %s is immutable", terminal)
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE job_id = ? AND type = 'claim'", Integer.class, job))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE job_id = ? AND type = 'commit'", Integer.class, job))
                .isEqualTo(1);
    }

    // ---- Flood harness ----

    /**
     * Runs {@link #POOL} workers on {@link JobService#claimNext} until every job is done.
     * {@code t_flood} queues {@link #FLOOD_JOBS} up front; {@code t_quiet} submits one job
     * after every 20 flood claims, starting at 40. Each job is claimed, held for
     * {@link #HOLD_MILLIS} and
     * committed with a capability.
     */
    private FloodRun flood(int floodQuota) throws Exception {
        tenant("t_flood", floodQuota);
        tenant("t_quiet", 3);
        seedJobs("t_flood", FLOOD_JOBS);
        UUID quietDoc = document("t_quiet");

        AtomicInteger floodClaims = new AtomicInteger();
        Map<UUID, Integer> submittedAt = new ConcurrentHashMap<>();
        Map<UUID, Integer> claimedAt = new ConcurrentHashMap<>();
        Map<UUID, Integer> floodQueuedAtCommit = new ConcurrentHashMap<>();
        Map<String, Integer> maxRunning = new ConcurrentHashMap<>();
        AtomicBoolean done = new AtomicBoolean();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(POOL + 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                // Quiet submitter: paced by flood progress, not by time.
                for (int i = 0; i < QUIET_JOBS; i++) {
                    int mark = 40 + 20 * i;
                    while (floodClaims.get() < mark && !done.get()) {
                        pause(1L);
                    }
                    UUID id = queue("t_quiet", quietDoc, "quiet-" + i);
                    submittedAt.put(id, floodClaims.get());
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                // Sampler: highest RUNNING count seen per tenant.
                while (!done.get()) {
                    sampleRunning(maxRunning);
                    pause(1L);
                }
                return null;
            }));
            for (int w = 0; w < POOL; w++) {
                String owner = "flood-w" + w;
                futures.add(pool.submit(() -> {
                    try {
                        while (!done.get()) {
                            Optional<TenantClaim> next = jobs.claimNext(owner);
                            if (next.isEmpty()) {
                                if (openJobs() == 0 && submittedAt.size() == QUIET_JOBS) {
                                    done.set(true);
                                }
                                pause(1L);
                                continue;
                            }
                            TenantClaim c = next.get();
                            if (c.tenantId().equals("t_flood")) {
                                floodClaims.incrementAndGet();
                            } else {
                                claimedAt.put(c.job().jobId(), floodClaims.get());
                            }
                            int running = running(c.tenantId());
                            maxRunning.merge(c.tenantId(), running, Math::max);
                            pause(HOLD_MILLIS);
                            jobs.issueCap(c.tenantId(), c.job().jobId());
                            if (!jobs.commitSucceeded(c.tenantId(), c.job().jobId(), owner, c.job().fencingToken())) {
                                throw new AssertionError("live holder " + owner + " could not commit " + c.job().jobId());
                            }
                            if (c.tenantId().equals("t_quiet")) {
                                floodQueuedAtCommit.put(c.job().jobId(), queued("t_flood"));
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                        done.set(true);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } finally {
            done.set(true);
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        assertThat(errors).as("worker errors").isEmpty();
        sampleRunning(maxRunning);
        return new FloodRun(floodQuota, submittedAt, claimedAt, floodQueuedAtCommit, maxRunning);
    }

    private record FloodRun(
            int floodQuota,
            Map<UUID, Integer> submittedAt,
            Map<UUID, Integer> claimedAt,
            Map<UUID, Integer> floodQueuedAtCommit,
            Map<String, Integer> maxRunning) {

        void assertQuietTenantProgressed() {
            assertThat(submittedAt).hasSize(QUIET_JOBS);
            assertThat(claimedAt.keySet()).as("every quiet job was claimed").isEqualTo(submittedAt.keySet());
            int worst = 0;
            for (Map.Entry<UUID, Integer> e : submittedAt.entrySet()) {
                int ahead = claimedAt.get(e.getKey()) - e.getValue();
                worst = Math.max(worst, ahead);
                assertThat(ahead).as("flood claims between quiet job %s's submit and its claim", e.getKey())
                        .isLessThanOrEqualTo(MAX_FLOOD_CLAIMS_AHEAD);
            }
            assertThat(floodQueuedAtCommit.values()).as("every quiet job finished while the flood still queued work")
                    .allMatch(q -> q > 0);
            assertThat(maxRunning.getOrDefault("t_flood", 0)).as("flood within quota").isLessThanOrEqualTo(floodQuota);
            assertThat(maxRunning.getOrDefault("t_quiet", 0)).as("quiet within quota").isLessThanOrEqualTo(3);
            String summary = "FLOOD quota=" + floodQuota + " pool=" + POOL + " floodJobs=" + FLOOD_JOBS
                    + " quietJobs=" + QUIET_JOBS + " worstFloodClaimsAhead=" + worst
                    + " maxRunning=" + new TreeMap<>(maxRunning)
                    + " minFloodQueuedAtQuietCommit=" + floodQueuedAtCommit.values().stream().min(Integer::compare).orElse(-1);
            System.out.println(summary);
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

    private UUID queue(String tenant, UUID doc, String key) {
        return jdbc.queryForObject(
                "INSERT INTO jobs (tenant_id, document_id, version_id, idempotency_key) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, doc, doc, key);
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

    private UUID terminal(String tenant, String state) {
        UUID doc = document(tenant);
        return jdbc.queryForObject(
                "INSERT INTO jobs (tenant_id, document_id, version_id, idempotency_key, state, next_run_at) "
                        + "VALUES (?, ?, ?, ?, ?, clock_timestamp() - INTERVAL '1 hour') RETURNING id",
                UUID.class, tenant, doc, doc, "terminal-" + state, state);
    }

    private void expireLease(UUID job) {
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
    }

    private void sampleRunning(Map<String, Integer> maxRunning) {
        jdbc.query("SELECT tenant_id, count(*) AS n FROM jobs WHERE state = 'RUNNING' GROUP BY tenant_id",
                rs -> {
                    maxRunning.merge(rs.getString("tenant_id"), rs.getInt("n"), Math::max);
                });
    }

    private int running(String tenant) {
        return count("SELECT count(*) FROM jobs WHERE tenant_id = ? AND state = 'RUNNING'", tenant);
    }

    private int queued(String tenant) {
        return count("SELECT count(*) FROM jobs WHERE tenant_id = ? AND state = 'QUEUED'", tenant);
    }

    private int attempts(String tenant) {
        return count("SELECT count(*) FROM attempts WHERE tenant_id = ?", tenant);
    }

    private int openJobs() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE state IN ('QUEUED', 'RUNNING')", Integer.class);
        return n == null ? 0 : n;
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

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
