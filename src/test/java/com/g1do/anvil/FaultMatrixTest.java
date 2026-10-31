package com.g1do.anvil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;

import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.TenantClaim;
import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxPublisher;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.submit.SubmitService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres fault matrix for issue #78.
 *
 * <p>Six fault points from the failure model (crash before the effect, crash
 * after it, lost ack, duplicate delivery, out-of-order delivery, stuck lease),
 * plus a GIN pending-list flush, autovacuum and connection-pool exhaustion.
 * Every case ends in {@link #assertSingleEffectNoLostWork}: each job is
 * {@code SUCCEEDED} with exactly one commit in its audit chain, its ledger has
 * one row per claim, and its outbox event is sent and applied exactly once.
 *
 * <p>Crashes and the stuck lease use a real worker process
 * ({@link WorkerFaultChild}) that is {@code kill -9}ed or {@code kill -STOP}ped.
 * Lease expiry moves the lease on the database clock instead of waiting the
 * real 10 seconds; {@code JobServiceTest#zombieFenceRealSuspend} covers the
 * wall-clock wait. GIN and autovacuum are induced on this test database only and
 * reset afterwards; the schema has no GIN index.
 *
 * <p>Random choices (which claims stall, title words) come from
 * {@code FAULT_SEED}. A failure writes the seed and trace to
 * {@code target/fault-runs/} ({@link FaultReport}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(FaultReport.class)
class FaultMatrixTest implements FaultReport.Seeded {

    static final long DEFAULT_SEED = 0xFA1178L;
    static final String TENANT = "t_fault";

    private static final Pattern CHILD_REPLY = Pattern.compile("^(READY|CLAIMED|NO_WORK|HEARTBEAT|COMMIT|UNKNOWN)\\b.*");
    private static final String[] VACUUMED = {"jobs", "attempts", "outbox", "processed_events", "audit_events", "cap_grants"};

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @Autowired
    OutboxRelay relay;

    @Autowired
    IdempotentConsumer consumer;

    @Autowired
    SubmitService submits;

    @Autowired
    DataSource dataSource;

    private final long seed = FaultReport.seed(DEFAULT_SEED);
    private final Random random = new Random(seed);
    private final Map<UUID, AtomicInteger> applies = new ConcurrentHashMap<>();
    private final Object reconciler = new Object();

    /** Forwards to the idempotent consumer and counts each event it applies. */
    private final OutboxPublisher apply = (event, headers) -> {
        if (consumer.consume(event)) {
            applies.computeIfAbsent(event.eventId(), id -> new AtomicInteger()).incrementAndGet();
        }
    };

    @Override
    public long seed() {
        return seed;
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
        FaultReport.step("seed=" + seed);
    }

    // --- Six fault points -------------------------------------------------

    @Test
    void crashBeforeEffect() throws Exception {
        UUID job = submit("crash-before");
        try (Worker a = Worker.start(coords(), "worker-A")) {
            assertThat(a.send("claim")).startsWith("CLAIMED " + job);
            jobs.issueCap(TENANT, job);
            a.kill9();
        }
        assertThat(state(job)).as("the crash left the job held, not finished").isEqualTo("RUNNING");

        expireLease(job);
        assertThat(jobs.reconcile(TENANT).releasedToQueued()).isEqualTo(1);
        finishQueued("worker-B");
        deliverAll();

        assertSingleEffectNoLostWork("crash-before-effect", List.of(job));
        assertThat(attempts(job)).as("the crashed claim stays in the ledger").isEqualTo(2);
    }

    @Test
    void crashAfterEffect() throws Exception {
        UUID job = submit("crash-after");
        try (Worker a = Worker.start(coords(), "worker-A")) {
            assertThat(a.send("claim")).startsWith("CLAIMED " + job);
            jobs.issueCap(TENANT, job);
            assertThat(a.send("commit")).isEqualTo("COMMIT true");
            a.kill9();
        }
        FaultReport.step("worker-A killed after its commit, before reporting it");

        assertThat(jobs.reconcile(TENANT).releasedToQueued()).as("a finished job is never released").isZero();
        assertThat(jobs.claim(TENANT, "worker-B")).as("nor offered again").isEmpty();
        assertThat(submits.submit(TENANT, "crash-after", "fault crash-after", "text/plain", "body-crash-after").jobId())
                .as("the client's retry gets the same job").isEqualTo(job);
        deliverAll();

        assertSingleEffectNoLostWork("crash-after-effect", List.of(job));
        assertThat(attempts(job)).isEqualTo(1);
    }

    @Test
    void lostAck() {
        UUID job = submit("lost-ack");
        ClaimedJob a = jobs.claim(TENANT, "worker-A").orElseThrow();
        jobs.issueCap(TENANT, job);
        assertThat(jobs.commitSucceeded(TENANT, job, "worker-A", a.fencingToken())).isTrue();
        FaultReport.step("commit ack lost; worker-A retries the commit");
        assertThat(jobs.commitSucceeded(TENANT, job, "worker-A", a.fencingToken()))
                .as("the retried commit affects 0 rows").isFalse();

        OutboxPublisher ackLost = (event, headers) -> {
            apply.publish(event, headers);
            throw new IllegalStateException("delivery ack lost for " + event.eventId());
        };
        assertThatThrownBy(() -> relay.relayOnce(ackLost, TENANT)).hasMessageContaining("delivery ack lost");
        FaultReport.step("consumer applied the event; its ack never reached the relay");
        assertThat(unsent(job)).as("without the ack the relay doesn't mark the event sent").isEqualTo(1);
        deliverAll();

        assertSingleEffectNoLostWork("lost-ack", List.of(job));
    }

    @Test
    void duplicateDelivery() {
        UUID job = submit("dupe");
        finishQueued("worker-A");

        AtomicInteger deliveries = new AtomicInteger();
        OutboxPublisher second = (event, headers) -> {
            deliveries.incrementAndGet();
            apply.publish(event, headers);
        };
        OutboxPublisher first = (event, headers) -> {
            deliveries.incrementAndGet();
            // A second relay polls between this one's publish and mark, so both deliver.
            relay.relayOnce(second, TENANT);
            apply.publish(event, headers);
        };
        assertThat(relay.relayOnce(first, TENANT)).as("the first relay's mark is a no-op").isZero();
        assertThat(deliveries.get()).as("two relays delivered the same event").isEqualTo(2);
        UUID event = jdbc.queryForObject("SELECT id FROM outbox WHERE job_id = ?", UUID.class, job);
        assertThat(new IdempotentConsumer(jdbc, new DataSourceTransactionManager(dataSource))
                .consume(new OutboxEvent(event, TENANT, job, null, null, "job.committed", null)))
                .as("a third delivery to a fresh consumer").isFalse();
        FaultReport.step("event " + event + " delivered 3 times");

        assertSingleEffectNoLostWork("duplicate-delivery", List.of(job));
    }

    @Test
    void outOfOrderDelivery() {
        List<UUID> submitted = List.of(submit("order-1"), submit("order-2"), submit("order-3"));
        List<ClaimedJob> claimed = new ArrayList<>();
        for (String worker : List.of("worker-A", "worker-B", "worker-C")) {
            claimed.add(jobs.claim(TENANT, worker).orElseThrow());
        }
        for (ClaimedJob c : claimed.reversed()) {
            jobs.issueCap(TENANT, c.jobId());
            assertThat(jobs.commitSucceeded(TENANT, c.jobId(), c.owner(), c.fencingToken())).isTrue();
            FaultReport.step("commit " + c.jobId() + " by " + c.owner());
        }

        // A broker that accepts every event, then delivers them newest first and the oldest twice.
        List<OutboxEvent> accepted = new ArrayList<>();
        assertThat(relay.relayOnce((event, headers) -> accepted.add(event), TENANT)).isEqualTo(3);
        List<OutboxEvent> delivered = new ArrayList<>(accepted.reversed());
        delivered.add(accepted.getFirst());
        for (OutboxEvent event : delivered) {
            apply.publish(event, Map.of("event_id", event.eventId().toString()));
            FaultReport.step("deliver " + event.eventId() + " for job " + event.jobId());
        }

        assertSingleEffectNoLostWork("out-of-order-delivery", submitted);
    }

    @Test
    void stuckLease() throws Exception {
        UUID job = submit("stuck");
        try (Worker a = Worker.start(coords(), "worker-A")) {
            String[] claim = a.send("claim").split(" ");
            assertThat(claim[1]).isEqualTo(job.toString());
            jobs.issueCap(TENANT, job);

            a.stop();
            expireLease(job);
            assertThat(jobs.reconcile(TENANT).releasedToQueued()).isEqualTo(1);
            // The successor reuses worker-A's ID (a restart on the same host), so only the
            // fencing token tells the suspended process apart from it.
            ClaimedJob successor = jobs.claim(TENANT, "worker-A").orElseThrow();
            assertThat(successor.fencingToken()).isEqualTo(Long.parseLong(claim[2]) + 1);
            FaultReport.step("successor worker-A holds the job with token " + successor.fencingToken());

            a.resume();
            assertThat(a.send("heartbeat")).as("the resumed worker's heartbeat").isEqualTo("HEARTBEAT false");
            assertThat(a.send("commit")).as("and its commit affect 0 rows").isEqualTo("COMMIT false");
            assertThat(state(job)).as("while the successor still holds it").isEqualTo("RUNNING");
            assertThat(jobs.commitSucceeded(TENANT, job, "worker-A", successor.fencingToken())).isTrue();
        }
        deliverAll();

        assertSingleEffectNoLostWork("stuck-lease", List.of(job));
        assertThat(attempts(job)).isEqualTo(2);
    }

    // --- GIN, autovacuum, pool exhaustion ---------------------------------

    @Test
    void ginPendingListFlush() throws Exception {
        jdbc.execute("CREATE INDEX fault_gin_idx ON documents USING gin (to_tsvector('simple', title))"
                + " WITH (fastupdate = on, gin_pending_list_limit = 64)");
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong flushedPages = new AtomicLong();
        Thread cleaner = new Thread(() -> {
            while (!done.get()) {
                Long pages = jdbc.queryForObject("SELECT gin_clean_pending_list('fault_gin_idx'::regclass)", Long.class);
                flushedPages.addAndGet(pages == null ? 0 : pages);
                pause(5L);
            }
        }, "fault-gin-cleaner");
        try {
            cleaner.start();
            Set<UUID> submitted = submitConcurrently(40, 4, 60);
            workUntilDone(4);
            deliverAll();
            FaultReport.step("gin pending-list pages flushed while submitting: " + flushedPages.get());

            assertThat(flushedPages.get()).as("submits ran while the GIN pending list was flushed").isPositive();
            assertSingleEffectNoLostWork("gin-pending-list-flush", submitted);
        } finally {
            done.set(true);
            cleaner.join(TimeUnit.SECONDS.toMillis(30));
            jdbc.execute("DROP INDEX IF EXISTS fault_gin_idx");
        }
    }

    @Test
    void autovacuum() throws Exception {
        long before = vacuumRuns();
        for (String table : VACUUMED) {
            jdbc.execute("ALTER TABLE " + table + " SET (autovacuum_vacuum_threshold = 0,"
                    + " autovacuum_vacuum_scale_factor = 0, autovacuum_analyze_threshold = 0,"
                    + " autovacuum_analyze_scale_factor = 0)");
        }
        jdbc.execute("ALTER SYSTEM SET autovacuum_naptime = '1s'");
        jdbc.queryForObject("SELECT pg_reload_conf()", Boolean.class);
        AtomicBoolean done = new AtomicBoolean();
        // Manual VACUUM on two tables overlaps the work for sure; leaving the rest to
        // autovacuum keeps it from finding nothing to do.
        Thread vacuum = new Thread(() -> {
            while (!done.get()) {
                jdbc.execute("VACUUM (ANALYZE) jobs, outbox");
                pause(50L);
            }
        }, "fault-vacuum");
        try {
            vacuum.start();
            Set<UUID> submitted = new LinkedHashSet<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            int round = 0;
            while (vacuumRuns() <= before && System.nanoTime() < deadline) {
                round++;
                submitted.addAll(submitConcurrently(20, 4, 1));
                workUntilDone(4);
                deliverAll();
                FaultReport.step("round " + round + ": autovacuum and autoanalyze runs so far " + (vacuumRuns() - before));
            }

            assertThat(vacuumRuns()).as("autovacuum ran on the job tables during the work").isGreaterThan(before);
            assertSingleEffectNoLostWork("autovacuum", submitted);
        } finally {
            done.set(true);
            vacuum.join(TimeUnit.SECONDS.toMillis(30));
            for (String table : VACUUMED) {
                jdbc.execute("ALTER TABLE " + table + " RESET (autovacuum_vacuum_threshold,"
                        + " autovacuum_vacuum_scale_factor, autovacuum_analyze_threshold,"
                        + " autovacuum_analyze_scale_factor)");
            }
            jdbc.execute("ALTER SYSTEM RESET autovacuum_naptime");
            jdbc.queryForObject("SELECT pg_reload_conf()", Boolean.class);
        }
    }

    @Test
    void poolExhaustion() throws Exception {
        HikariDataSource main = (HikariDataSource) dataSource;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(main.getJdbcUrl());
        config.setUsername(main.getUsername());
        config.setPassword(main.getPassword());
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(250);
        config.setPoolName("fault-small-pool");
        try (HikariDataSource small = new HikariDataSource(config)) {
            JdbcTemplate smallJdbc = new JdbcTemplate(small);
            JobService smallJobs = new JobService(smallJdbc, new DataSourceTransactionManager(small));
            OutboxRelay smallRelay = new OutboxRelay(smallJdbc, new DataSourceTransactionManager(small));

            UUID job = submit("pool");
            ClaimedJob a = smallJobs.claim(TENANT, "worker-A").orElseThrow();
            jobs.issueCap(TENANT, job);

            List<Connection> held = List.of(small.getConnection(), small.getConnection());
            FaultReport.step("worker-A's pool exhausted: " + held.size() + " of 2 connections held");
            try {
                assertThatThrownBy(() -> smallJobs.heartbeat(TENANT, job, "worker-A", a.fencingToken()))
                        .isInstanceOf(CannotCreateTransactionException.class)
                        .hasRootCauseInstanceOf(SQLTransientConnectionException.class);
                assertThatThrownBy(() -> smallJobs.commitSucceeded(TENANT, job, "worker-A", a.fencingToken()))
                        .isInstanceOf(CannotCreateTransactionException.class);
                assertThatThrownBy(() -> smallRelay.relayOnce(apply, TENANT))
                        .isInstanceOf(CannotCreateTransactionException.class);
                assertThat(state(job)).as("nothing changed while the pool was exhausted").isEqualTo("RUNNING");
                assertThat(unsent(job)).isEqualTo(1);

                expireLease(job);
                assertThat(jobs.reconcile(TENANT).releasedToQueued()).isEqualTo(1);
                finishQueued("worker-B");
            } finally {
                for (Connection connection : held) {
                    connection.close();
                }
            }
            FaultReport.step("worker-A's pool released");
            assertThat(smallJobs.commitSucceeded(TENANT, job, "worker-A", a.fencingToken()))
                    .as("worker-A's late commit affects 0 rows").isFalse();
            while (smallRelay.relayOnce(apply, TENANT) > 0) {
                FaultReport.step("relay pass on the recovered pool");
            }

            assertSingleEffectNoLostWork("pool-exhaustion", List.of(job));
        }
    }

    // --- Harness ----------------------------------------------------------

    private UUID submit(String key) {
        UUID job = submits.submit(TENANT, key, "fault " + key, "text/plain", "body-" + key).jobId();
        FaultReport.step("submit " + key + " -> job " + job);
        return job;
    }

    /**
     * Submits {@code keys} documents from {@code threads} threads, each key twice at once
     * (a client retrying a slow submit). Every pair must get the same job.
     */
    private Set<UUID> submitConcurrently(int keys, int threads, int titleWords) throws Exception {
        String prefix = Long.toHexString(random.nextLong());
        List<String> titles = new ArrayList<>();
        for (int k = 0; k < keys; k++) {
            StringBuilder title = new StringBuilder("fault");
            for (int w = 0; w < titleWords; w++) {
                title.append(' ').append(Integer.toString(random.nextInt(1 << 20), 36));
            }
            titles.add(title.toString());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int k = 0; k < keys; k++) {
                String key = "fault-" + prefix + "-" + k;
                String title = titles.get(k);
                for (int copy = 0; copy < 2; copy++) {
                    futures.add(pool.submit(() -> submits.submit(TENANT, key, title, "text/plain", "body-" + key).jobId()));
                }
            }
            Set<UUID> submitted = new LinkedHashSet<>();
            for (int k = 0; k < keys; k++) {
                UUID first = futures.get(2 * k).get(60, TimeUnit.SECONDS);
                UUID retry = futures.get(2 * k + 1).get(60, TimeUnit.SECONDS);
                assertThat(retry).as("both submits of key %d get one job", k).isEqualTo(first);
                submitted.add(first);
            }
            FaultReport.step("submitted " + keys + " keys twice each from " + threads + " threads");
            return submitted;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Claims and commits every queued job as {@code owner}. */
    private void finishQueued(String owner) {
        Optional<ClaimedJob> claimed;
        while ((claimed = jobs.claim(TENANT, owner)).isPresent()) {
            ClaimedJob c = claimed.get();
            jobs.issueCap(TENANT, c.jobId());
            assertThat(jobs.commitSucceeded(TENANT, c.jobId(), owner, c.fencingToken())).isTrue();
            FaultReport.step("commit " + c.jobId() + " by " + owner + " t" + c.fencingToken());
        }
    }

    /**
     * Runs {@code workers} worker threads, each claiming with {@link JobService#claimNext}, until
     * no job is open. On a first attempt a worker
     * stalls past its lease one time in three (seeded): the reconciler hands the job on and
     * the late commit must affect 0 rows. Later attempts always commit, so no job runs out
     * of attempts.
     */
    private void workUntilDone(int workers) throws Exception {
        long[] seeds = random.longs(workers).toArray();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String owner = "w" + w;
                Random mine = new Random(seeds[w]);
                futures.add(pool.submit(() -> {
                    work(owner, mine);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void work(String owner, Random rnd) throws InterruptedException {
        while (openJobs() > 0) {
            // The fair claim across tenants (#35); only TENANT has jobs here.
            Optional<TenantClaim> claimed = jobs.claimNext(owner);
            if (claimed.isEmpty()) {
                Thread.sleep(10L);
                continue;
            }
            if (!TENANT.equals(claimed.get().tenantId())) {
                throw new AssertionError(owner + " claimed another tenant's job: " + claimed.get());
            }
            ClaimedJob c = claimed.get().job();
            jobs.issueCap(TENANT, c.jobId());
            if (c.attempt() == 1 && rnd.nextInt(3) == 0) {
                expireLease(c.jobId());
                synchronized (reconciler) {
                    jobs.reconcile(TENANT);
                }
                // Commit late once another worker holds the job, so fencing, not the state, refuses it.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ("QUEUED".equals(state(c.jobId())) && System.nanoTime() < deadline) {
                    Thread.sleep(5L);
                }
                boolean late = jobs.commitSucceeded(TENANT, c.jobId(), owner, c.fencingToken());
                FaultReport.step(owner + " stalled on " + c.jobId() + " t" + c.fencingToken() + "; late commit=" + late);
                if (late) {
                    throw new AssertionError("stale commit by " + owner + " t" + c.fencingToken() + " took effect");
                }
            } else {
                boolean ok = jobs.commitSucceeded(TENANT, c.jobId(), owner, c.fencingToken());
                FaultReport.step(owner + " commit " + c.jobId() + " t" + c.fencingToken() + "=" + ok);
                if (!ok) {
                    throw new AssertionError("live holder " + owner + " t" + c.fencingToken() + " could not commit");
                }
            }
        }
    }

    private void deliverAll() {
        int marked;
        while ((marked = relay.relayOnce(apply, TENANT)) > 0) {
            FaultReport.step("relay marked " + marked);
        }
    }

    private void expireLease(UUID job) {
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
        FaultReport.step("lease of " + job + " expired on the database clock");
    }

    private long vacuumRuns() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(autovacuum_count + autoanalyze_count), 0)"
                + " FROM pg_stat_user_tables WHERE relname = ANY (?)", Long.class, (Object) VACUUMED);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String state(UUID job) {
        return jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, job);
    }

    private int attempts(UUID job) {
        return count("SELECT COUNT(*) FROM attempts WHERE job_id = ?", job);
    }

    private int unsent(UUID job) {
        return count("SELECT COUNT(*) FROM outbox WHERE job_id = ? AND sent_at IS NULL", job);
    }

    private int openJobs() {
        return count("SELECT COUNT(*) FROM jobs WHERE tenant_id = ? AND state IN ('QUEUED', 'RUNNING')", TENANT);
    }

    private int count(String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    /**
     * Single effect: each job is {@code SUCCEEDED} with one commit in its audit chain, one
     * ledger row per claim with distinct tokens, and each of its outbox events applied by
     * the consumer exactly once. No lost work: no job is left open, dead or failed, and
     * no event is unsent or unapplied.
     */
    private void assertSingleEffectNoLostWork(String faultCase, Collection<UUID> jobIds) {
        assertThat(jobIds).as("case has jobs").isNotEmpty();
        int events = 0;
        for (UUID job : jobIds) {
            Map<String, Object> row = jdbc.queryForMap("SELECT state, attempt FROM jobs WHERE id = ?", job);
            assertThat(row.get("state")).as("job %s finished (no lost work)", job).isEqualTo("SUCCEEDED");
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE job_id = ? AND type = 'commit'", job))
                    .as("job %s committed once (single effect)", job).isEqualTo(1);
            int ledger = attempts(job);
            assertThat(ledger).as("job %s ledger has one row per claim", job)
                    .isEqualTo(((Number) row.get("attempt")).intValue());
            assertThat(count("SELECT COUNT(DISTINCT fencing_token) FROM attempts WHERE job_id = ?", job))
                    .as("job %s claims had distinct tokens", job).isEqualTo(ledger);
            List<UUID> outbox = jdbc.queryForList("SELECT id FROM outbox WHERE job_id = ?", UUID.class, job);
            assertThat(outbox).as("job %s has its event", job).isNotEmpty();
            for (UUID event : outbox) {
                assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM outbox WHERE id = ?", Boolean.class, event))
                        .as("event %s sent (no lost work)", event).isTrue();
                assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", event))
                        .as("event %s recorded once", event).isEqualTo(1);
                AtomicInteger applied = applies.get(event);
                assertThat(applied == null ? 0 : applied.get()).as("event %s applied once (single effect)", event)
                        .isEqualTo(1);
                events++;
            }
        }
        String summary = "FAULT case=" + faultCase + " seed=" + seed + " jobs=" + jobIds.size()
                + " commits=" + jobIds.size() + " events=" + events + " applied-once=" + events + " lost=0";
        FaultReport.step(summary);
        System.out.println(summary);
    }

    private String[] coords() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        String password = hikari.getPassword() == null ? "__NULL__" : hikari.getPassword();
        return new String[]{hikari.getJdbcUrl(), hikari.getUsername(), password};
    }

    /** A {@link WorkerFaultChild} process driven over stdin and stdout. */
    private static final class Worker implements AutoCloseable {

        private final Process process;
        private final Writer stdin;
        private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        private final String owner;

        private Worker(Process process, String owner) {
            this.process = process;
            this.owner = owner;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            Thread drain = new Thread(() -> {
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        // The child's own logging shares stdout; only replies are answers.
                        if (CHILD_REPLY.matcher(line).matches()) {
                            replies.add(line);
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "fault-worker-" + owner);
            drain.setDaemon(true);
            drain.start();
        }

        static Worker start(String[] coords, String owner) throws Exception {
            String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    WorkerFaultChild.class.getName(), coords[0], coords[1], coords[2], TENANT, owner)
                    .redirectErrorStream(true)
                    .start();
            Worker worker = new Worker(process, owner);
            assertThat(worker.next()).as("%s started", owner).isEqualTo("READY");
            FaultReport.step(owner + " started as pid " + process.pid());
            return worker;
        }

        String send(String command) throws Exception {
            stdin.write(command + "\n");
            stdin.flush();
            String reply = next();
            FaultReport.step(owner + " " + command + " -> " + reply);
            return reply;
        }

        private String next() throws InterruptedException {
            String reply = replies.poll(30, TimeUnit.SECONDS);
            assertThat(reply).as("%s replied", owner).isNotNull();
            return reply;
        }

        void kill9() throws Exception {
            signal("-9");
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("%s died", owner).isTrue();
            FaultReport.step(owner + " kill -9, exit " + process.exitValue());
        }

        void stop() throws Exception {
            signal("-STOP");
            Path stat = Path.of("/proc", Long.toString(process.pid()), "stat");
            if (Files.exists(stat)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (procState(stat) != 'T' && System.nanoTime() < deadline) {
                    Thread.sleep(20L);
                }
                assertThat(procState(stat)).as("%s is stopped", owner).isEqualTo('T');
            }
            FaultReport.step(owner + " kill -STOP");
        }

        void resume() throws Exception {
            signal("-CONT");
            FaultReport.step(owner + " kill -CONT");
        }

        private void signal(String signal) throws Exception {
            Process kill = new ProcessBuilder("kill", signal, Long.toString(process.pid())).start();
            assertThat(kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0)
                    .as("kill %s %s", signal, owner).isTrue();
        }

        /** The state letter after the command name in {@code /proc/<pid>/stat}. */
        private static char procState(Path stat) throws Exception {
            String text = Files.readString(stat);
            return text.charAt(text.lastIndexOf(')') + 2);
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
