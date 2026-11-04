package com.g1do.anvil.observe;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.tenant.TenantGuard;

/**
 * Saturation signals for issue #33 (REQ-4 burn part).
 *
 * <p>Three signals, all tenant-free in labels and output:
 * <ul>
 * <li>{@code anvil_queue_age_seconds} gauge: oldest QUEUED age on the DB clock
 * ({@code clock_timestamp() - MIN(created_at)}). Computed per tenant under RLS
 * with {@link TenantGuard#setTenant} then reduced to a global max, so pooled
 * reuse stays fenced and no tenant id is exposed.</li>
 * <li>{@code anvil_claim_latency_seconds} p99: p99 of {@code JobService.claim}
 * wall latency, recorded in-process with a bounded sample ring.</li>
 * <li>{@code anvil_pool_wait_seconds} p99: p99 of {@code DataSource.getConnection}
 * acquisition latency, sampled on scrape.</li>
 * </ul>
 *
 * <p>Aggregation is p99 over the last 1024 samples; the gauge is instantaneous.
 * Labels are static ({@code state="queued"}, {@code op="claim"},
 * {@code pool="hikaricp"}) — never tenant ids.
 */
@Service
public class SaturationSignals {

    public static final String QUEUE_AGE_METRIC = "anvil_queue_age_seconds";
    public static final String CLAIM_LATENCY_METRIC = "anvil_claim_latency_seconds";
    public static final String POOL_WAIT_METRIC = "anvil_pool_wait_seconds";

    private static final Logger log = LoggerFactory.getLogger(SaturationSignals.class);

    private static final int MAX_SAMPLES = 1024;

    private static final String QUEUE_AGE_SQL =
            "SELECT EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))) "
                    + "FROM jobs WHERE state = 'QUEUED'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DataSource dataSource;

    private final Deque<Long> claimSamples = new ArrayDeque<>();
    private final Deque<Long> poolWaitSamples = new ArrayDeque<>();

    /** The last queue age read, reported while the database can't be read; 0 before the first read. */
    private volatile double lastQueueAgeSeconds;

    /** How long the latest pool sample took to fail; 0 once a sample succeeds. */
    private volatile long failedPoolWaitNanos;

    public SaturationSignals(
            JdbcTemplate jdbc, PlatformTransactionManager txManager, DataSource dataSource) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.dataSource = dataSource;
    }

    /**
     * Oldest QUEUED age in seconds on the DB clock, max across tenants.
     * Each tenant is read in its own transaction with RLS context set, so
     * collection preserves fencing and RLS and exposes no tenant bytes. When the
     * database can't be read the failure is logged and the last value reported: an
     * unread queue isn't an empty one. Before any successful read that value is 0;
     * when the pool is the cause, {@link #poolWaitP99Seconds} carries the alert.
     */
    public double queueAgeSeconds() {
        try {
            double max = 0.0;
            for (String tenant : jdbc.queryForList("SELECT id FROM tenants", String.class)) {
                Double age = tx.execute(status -> {
                    TenantGuard.setTenant(jdbc, tenant);
                    return jdbc.queryForObject(QUEUE_AGE_SQL, Double.class);
                });
                if (age != null && age > max) {
                    max = age;
                }
            }
            lastQueueAgeSeconds = max;
            return max;
        } catch (Exception e) {
            // The exception's type only: its message can quote a statement's tenant value.
            log.warn("queue age could not be read: {}", e.getClass().getName());
            return lastQueueAgeSeconds;
        }
    }

    /** Records one claim latency sample (nanoseconds). Bounded ring, drops oldest. */
    public void recordClaimNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        synchronized (claimSamples) {
            claimSamples.addLast(nanos);
            while (claimSamples.size() > MAX_SAMPLES) {
                claimSamples.removeFirst();
            }
        }
    }

    /** Records one pool-wait sample (nanoseconds). Bounded ring, drops oldest. */
    public void recordPoolWaitNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        synchronized (poolWaitSamples) {
            poolWaitSamples.addLast(nanos);
            while (poolWaitSamples.size() > MAX_SAMPLES) {
                poolWaitSamples.removeFirst();
            }
        }
    }

    /** Samples one pool connection acquisition and records it. Returns seconds. */
    public double samplePoolWaitSeconds() {
        long start = System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            c.isValid(2);
        } catch (Exception e) {
            // Counts only while the pool is failing. In the sample ring, one blip would
            // stay the p99 for the next hundred scrapes, long after recovery.
            failedPoolWaitNanos = System.nanoTime() - start;
            return failedPoolWaitNanos / 1_000_000_000.0;
        }
        failedPoolWaitNanos = 0;
        long waited = System.nanoTime() - start;
        recordPoolWaitNanos(waited);
        return waited / 1_000_000_000.0;
    }

    /** p99 claim latency in seconds, 0 when no samples. */
    public double claimP99Seconds() {
        return p99Seconds(claimSamples);
    }

    /** p99 pool-wait latency in seconds, 0 when no samples. */
    public double poolWaitP99Seconds() {
        return Math.max(p99Seconds(poolWaitSamples), failedPoolWaitNanos / 1_000_000_000.0);
    }

    private static double p99Seconds(Deque<Long> samples) {
        List<Long> copy;
        synchronized (samples) {
            if (samples.isEmpty()) {
                return 0.0;
            }
            copy = new ArrayList<>(samples);
        }
        Collections.sort(copy);
        int idx = (int) Math.ceil(0.99 * copy.size()) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= copy.size()) {
            idx = copy.size() - 1;
        }
        return copy.get(idx) / 1_000_000_000.0;
    }

    /** Clears in-memory samples (tests only). */
    public void reset() {
        synchronized (claimSamples) {
            claimSamples.clear();
        }
        synchronized (poolWaitSamples) {
            poolWaitSamples.clear();
        }
        failedPoolWaitNanos = 0;
    }

    /** Point-in-time snapshot with no tenant bytes. */
    public SaturationSnapshot snapshot() {
        double queueAge = queueAgeSeconds();
        samplePoolWaitSeconds();
        return new SaturationSnapshot(queueAge, claimP99Seconds(), poolWaitP99Seconds());
    }

    /** Immutable snapshot, seconds. */
    public record SaturationSnapshot(
            double queueAgeSeconds, double claimP99Seconds, double poolWaitP99Seconds) {
    }
}
