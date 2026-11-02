package com.g1do.anvil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.ReconcileResult;
import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxPublisher;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.submit.SubmitService;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reduced real-PG fault subset for issue #32 (REQ-4, box 3).
 *
 * <p>Containerized PG only (Testcontainers {@code postgres:18}); no prod
 * target, no TTL shortening, no fencing/clock/RLS/cap/CAS behaviour change.
 * Real suspend/kill fidelity:
 * <ul>
 *   <li>suspend-past-lease: DB-clock expiry + reconciler + stale-owner 0 rows
 *   with exactly-once ledger on containerized PG (wall-clock 12s suspend is
 *   proven by {@code JobServiceTest#zombieFenceRealSuspend});</li>
 *   <li>restart: real {@code kill -9} of {@code RelayCrashChild} between
 *   publish and mark, then restart re-publishes the same stable identity with
 *   single apply;</li>
 *   <li>dupe: redelivery applies once, fresh consumer still dedupes.</li>
 * </ul>
 *
 * <p>Part of the weekly real-Postgres fault runs (issue #78); a failure writes
 * a report for the run's issue ({@link FaultReport}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(FaultReport.class)
class FaultProofTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobService jobs;

    @Autowired
    OutboxRelay relay;

    @Autowired
    IdempotentConsumer consumer;

    @Autowired
    SubmitService submitService;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE extraction_dead_letters, extracted_facts, extractions, job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
    }

    private UUID seedQueued(String tenant, String key) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                doc, tenant, "title-" + key);
        UUID ver = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, tenant, doc, "a".repeat(63) + "0");
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, key);
        return job;
    }

    @Test
    void suspendPastLeaseAffectsZeroRowsWithExactlyOnceLedgerOnContainerizedPg() {
        UUID job = seedQueued("t_single", "fault-suspend-" + UUID.randomUUID());
        ClaimedJob a = jobs.claim("t_single", "worker-A").orElseThrow();
        assertThat(a.jobId()).isEqualTo(job);
        jobs.issueCap("t_single", job);

        // Suspend past production lease via the DB clock (containerized PG is the
        // authority; wall-clock SIGSTOP past the real 10s TTL is proven by
        // JobServiceTest#zombieFenceRealSuspend and not repeated here to keep
        // this subset fast).
        jdbc.update("UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", job);
        Boolean expired = jdbc.queryForObject(
                "SELECT lease_expires_at <= clock_timestamp() FROM jobs WHERE id = ?", Boolean.class, job);
        assertThat(expired).as("DB clock confirms expiry on containerized PG").isTrue();

        ReconcileResult released = jobs.reconcile("t_single");
        assertThat(released.releasedToQueued()).isEqualTo(1);

        ClaimedJob b = jobs.claim("t_single", "worker-B").orElseThrow();
        assertThat(b.fencingToken()).isEqualTo(a.fencingToken() + 1);

        assertThat(jobs.heartbeat("t_single", job, "worker-A", a.fencingToken())).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "worker-A", a.fencingToken())).isFalse();
        assertThat(jobs.commitSucceeded("t_single", job, "worker-B", b.fencingToken())).isTrue();

        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT attempt_number, owner, fencing_token FROM attempts WHERE job_id = ? ORDER BY attempt_number", job);
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0).get("owner")).isEqualTo("worker-A");
        assertThat(ledger.get(1).get("owner")).isEqualTo("worker-B");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ? AND state = 'SUCCEEDED'",
                Integer.class, job)).isEqualTo(1);
    }

    @Test
    void restartLosesNothingAndDedupesToOneApplyRealKill() throws Exception {
        submitService.submit("t_single", "fault-restart-" + UUID.randomUUID(), "t", "text/plain", "body");
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox ORDER BY id LIMIT 1", UUID.class);
        assertThat(eventId).isNotNull();

        String[] coords = dbCoords();
        String javaBin = javaBinary();
        String classpath = System.getProperty("java.class.path");
        Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                RelayCrashChild.class.getName(), coords[0], coords[1], coords[2], "t_single")
                .redirectErrorStream(true)
                .start();
        StringBuilder childOutput = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (childOutput) {
                        childOutput.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }, "faultproof-relay-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            boolean published = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
                if (count != null && count == 1) {
                    published = true;
                    break;
                }
                if (!child.isAlive()) {
                    break;
                }
                Thread.sleep(100L);
            }
            assertThat(published).as("crash child published before kill; output=%s", childOutput).isTrue();

            killNineOrDestroy(child);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.isAlive()).isFalse();

            assertThat(jdbc.queryForObject("SELECT sent_at FROM outbox WHERE id = ?", Object.class, eventId))
                    .as("crash before mark leaves no progress").isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);

            final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
            OutboxPublisher counting = (event, headers) -> {
                if (consumer.consume(event)) {
                    applied.incrementAndGet();
                }
            };
            int marked = relay.relayOnce(counting);
            assertThat(marked).isEqualTo(1);
            assertThat(applied.get()).as("redelivery must not re-apply").isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT sent_at FROM outbox WHERE id = ?", Object.class, eventId))
                    .isNotNull();

            IdempotentConsumer fresh = new IdempotentConsumer(jdbc, txManager);
            OutboxEvent redelivered = new OutboxEvent(eventId, "t_single", null, null, null, "job.committed", null);
            assertThat(fresh.consume(redelivered)).isFalse();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void dupeDeliveryAppliesOnceOnContainerizedPg() {
        submitService.submit("t_single", "fault-dupe-" + UUID.randomUUID(), "t", "text/plain", "body");
        final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
        final List<OutboxEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        OutboxPublisher counting = (event, headers) -> {
            seen.add(event);
            if (consumer.consume(event)) {
                applied.incrementAndGet();
            }
        };
        assertThat(relay.relayOnce(counting)).isEqualTo(1);
        assertThat(applied.get()).isEqualTo(1);
        OutboxEvent event = seen.get(0);

        assertThat(consumer.consume(event)).isFalse();
        assertThat(consumer.consume(event)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, event.eventId())).isEqualTo(1);

        IdempotentConsumer fresh = new IdempotentConsumer(jdbc, txManager);
        assertThat(fresh.consume(event)).isFalse();
    }

    private String[] dbCoords() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            String password = hikari.getPassword() == null ? "__NULL__" : hikari.getPassword();
            return new String[]{hikari.getJdbcUrl(), hikari.getUsername(), password};
        }
        throw new IllegalStateException("Hikari DataSource with JDBC URL required for crash child");
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        String bin = home + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            bin += ".exe";
        }
        return bin;
    }

    private static void killNineOrDestroy(Process process) throws Exception {
        long pid = process.pid();
        try {
            Process kill = new ProcessBuilder("kill", "-9", Long.toString(pid)).start();
            kill.waitFor(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50L);
            }
        } catch (Exception unavailable) {
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
