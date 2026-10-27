package com.g1do.anvil.observe;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import javax.sql.DataSource;

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

    private static final int MAX_SAMPLES = 1024;

    private static final String QUEUE_AGE_SQL =
            "SELECT EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))) "
                    + "FROM jobs WHERE state = 'QUEUED'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DataSource dataSource;

    private final Deque<Long> claimSamples = new ArrayDeque<>();
    private final Deque<Long> poolWaitSamples = new ArrayDeque<>();

    public SaturationSignals(
            JdbcTemplate jdbc, PlatformTransactionManager txManager, DataSource dataSource) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.dataSource = dataSource;
    }

    /**
     * Oldest QUEUED age in seconds on the DB clock, max across tenants.
     * Each tenant is read in its own transaction with RLS context set, so
     * collection preserves fencing and RLS and exposes no tenant bytes.
     */
    public double queueAgeSeconds() {
        List<String> tenants;
        try {
            tenants = jdbc.queryForList("SELECT id FROM tenants", String.class);
        } catch (Exception e) {
            return 0.0;
        }
        double max = 0.0;
        for (String tenant : tenants) {
            Double age = tx.execute(status -> {
                TenantGuard.setTenant(jdbc, tenant);
                return jdbc.queryForObject(QUEUE_AGE_SQL, Double.class);
            });
            if (age != null && age > max) {
                max = age;
            }
        }
        return max;
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
            return poolWaitP99Seconds();
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        recordPoolWaitNanos(System.nanoTime() - start);
        return seconds;
    }

    /** p99 claim latency in seconds, 0 when no samples. */
    public double claimP99Seconds() {
        return p99Seconds(claimSamples);
    }

    /** p99 pool-wait latency in seconds, 0 when no samples. */
    public double poolWaitP99Seconds() {
        return p99Seconds(poolWaitSamples);
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
