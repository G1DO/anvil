package com.g1do.anvil.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Burn alert evaluation for issue #33 (REQ-4 burn part).
 *
 * <p>Two alerts, stated thresholds and windows:
 * <ul>
 * <li>{@code QueueBurn}: {@code anvil_queue_age_seconds > 60 for 5m}.</li>
 * <li>{@code ClaimBurn}: {@code anvil_claim_latency_seconds p99 > 0.5 for 5m}
 * or {@code anvil_pool_wait_seconds p99 > 0.5 for 5m}.</li>
 * </ul>
 *
 * <p>Labels are static ({@code severity="page"}); payloads carry no tenant
 * bytes. Prometheus rules live in {@code prometheus/alerts-33.yml}; this class
 * is the same logic in-process so containerized tests can prove fire-on-burn
 * and silent-on-green without a Prometheus dependency.
 */
public final class BurnAlerts {

    public static final String QUEUE_BURN = "QueueBurn";
    public static final String CLAIM_BURN = "ClaimBurn";

    public static final double QUEUE_AGE_THRESHOLD_SECONDS = 60.0;
    public static final String QUEUE_WINDOW = "5m";

    public static final double CLAIM_P99_THRESHOLD_SECONDS = 0.5;
    public static final double POOL_WAIT_P99_THRESHOLD_SECONDS = 0.5;
    public static final String CLAIM_WINDOW = "5m";

    private BurnAlerts() {
    }

    /** One alert evaluation. */
    public record Firing(String alert, boolean firing, double value, double threshold, String window) {
    }

    /**
     * Evaluates both alerts against the given signal values.
     */
    public static List<Firing> evaluate(double queueAgeSeconds, double claimP99Seconds,
            double poolWaitP99Seconds) {
        List<Firing> out = new ArrayList<>(2);
        out.add(new Firing(QUEUE_BURN, queueAgeSeconds > QUEUE_AGE_THRESHOLD_SECONDS,
                queueAgeSeconds, QUEUE_AGE_THRESHOLD_SECONDS, QUEUE_WINDOW));
        boolean claimFire = claimP99Seconds > CLAIM_P99_THRESHOLD_SECONDS
                || poolWaitP99Seconds > POOL_WAIT_P99_THRESHOLD_SECONDS;
        double claimValue = Math.max(claimP99Seconds, poolWaitP99Seconds);
        double claimThreshold = Math.max(CLAIM_P99_THRESHOLD_SECONDS, POOL_WAIT_P99_THRESHOLD_SECONDS);
        out.add(new Firing(CLAIM_BURN, claimFire, claimValue, claimThreshold, CLAIM_WINDOW));
        return out;
    }

    /** True when any alert fires. */
    public static boolean anyFiring(List<Firing> firings) {
        for (Firing f : firings) {
            if (f.firing()) {
                return true;
            }
        }
        return false;
    }

    /** Alert payload with thresholds, windows, values; no tenant bytes. */
    public static Map<String, Object> payload(double queueAgeSeconds, double claimP99Seconds,
            double poolWaitP99Seconds) {
        List<Firing> firings = evaluate(queueAgeSeconds, claimP99Seconds, poolWaitP99Seconds);
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Firing f : firings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("alert", f.alert());
            entry.put("firing", f.firing());
            entry.put("value_seconds", f.value());
            entry.put("threshold_seconds", f.threshold());
            entry.put("window", f.window());
            entry.put("severity", "page");
            alerts.add(entry);
        }
        payload.put("alerts", alerts);
        payload.put("any_firing", anyFiring(firings));
        return payload;
    }
}
