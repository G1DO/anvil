package com.g1do.anvil.observe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saturation exposition for issue #33.
 *
 * <p>{@code GET /observe/saturation} returns JSON signals plus alert
 * evaluation. {@code GET /metrics} returns a minimal Prometheus text
 * exposition for the same three signals plus alert firing gauges. Both bypass
 * tenant auth by design (see {@code TenantAuthFilter#shouldNotFilter}: only
 * {@code /api/}, {@code /audit/}, {@code /documents}, {@code /submit} are
 * filtered) and carry no tenant bytes.
 */
@RestController
public class ObserveController {

    private final SaturationSignals signals;

    public ObserveController(SaturationSignals signals) {
        this.signals = signals;
    }

    /** JSON signals plus burn alert evaluation, no tenant bytes. */
    @GetMapping("/observe/saturation")
    public ResponseEntity<Map<String, Object>> saturation() {
        SaturationSignals.SaturationSnapshot snap = signals.snapshot();
        List<BurnAlerts.Firing> firings = BurnAlerts.evaluate(
                snap.queueAgeSeconds(), snap.claimP99Seconds(), snap.poolWaitP99Seconds());
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> signalMap = new LinkedHashMap<>();
        signalMap.put("anvil_queue_age_seconds",
                Map.of("value", snap.queueAgeSeconds(), "labels", Map.of("state", "queued"),
                        "aggregation", "gauge_db_clock_max_across_tenants"));
        signalMap.put("anvil_claim_latency_seconds_p99",
                Map.of("value", snap.claimP99Seconds(), "labels", Map.of("op", "claim"),
                        "aggregation", "p99_last_1024"));
        signalMap.put("anvil_pool_wait_seconds_p99",
                Map.of("value", snap.poolWaitP99Seconds(), "labels", Map.of("pool", "hikaricp"),
                        "aggregation", "p99_last_1024"));
        body.put("signals", signalMap);
        body.put("alerts", BurnAlerts.payload(
                snap.queueAgeSeconds(), snap.claimP99Seconds(), snap.poolWaitP99Seconds()));
        body.put("thresholds", Map.of(
                BurnAlerts.QUEUE_BURN, Map.of("threshold_seconds",
                        BurnAlerts.QUEUE_AGE_THRESHOLD_SECONDS, "window", BurnAlerts.QUEUE_WINDOW),
                BurnAlerts.CLAIM_BURN, Map.of("threshold_seconds",
                        BurnAlerts.CLAIM_P99_THRESHOLD_SECONDS, "window", BurnAlerts.CLAIM_WINDOW)));
        boolean anyTenantLeak = body.toString().contains("t_single");
        if (anyTenantLeak) {
            throw new IllegalStateException("saturation exposition leaked tenant bytes");
        }
        return ResponseEntity.ok(body);
    }

    /** Minimal Prometheus text exposition, no tenant bytes. */
    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4")
    public ResponseEntity<String> metrics() {
        SaturationSignals.SaturationSnapshot snap = signals.snapshot();
        List<BurnAlerts.Firing> firings = BurnAlerts.evaluate(
                snap.queueAgeSeconds(), snap.claimP99Seconds(), snap.poolWaitP99Seconds());
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP anvil_queue_age_seconds Oldest QUEUED age on DB clock.\n");
        sb.append("# TYPE anvil_queue_age_seconds gauge\n");
        sb.append("anvil_queue_age_seconds{state=\"queued\"} ").append(snap.queueAgeSeconds()).append("\n");
        sb.append("# HELP anvil_claim_latency_seconds Claim latency.\n");
        sb.append("# TYPE anvil_claim_latency_seconds summary\n");
        sb.append("anvil_claim_latency_seconds{op=\"claim\",quantile=\"0.99\"} ")
                .append(snap.claimP99Seconds()).append("\n");
        sb.append("# HELP anvil_pool_wait_seconds Pool connection wait.\n");
        sb.append("# TYPE anvil_pool_wait_seconds summary\n");
        sb.append("anvil_pool_wait_seconds{pool=\"hikaricp\",quantile=\"0.99\"} ")
                .append(snap.poolWaitP99Seconds()).append("\n");
        sb.append("# HELP anvil_burn_alert_firing Burn alert firing 1 or silent 0.\n");
        sb.append("# TYPE anvil_burn_alert_firing gauge\n");
        for (BurnAlerts.Firing f : firings) {
            sb.append("anvil_burn_alert_firing{alert=\"").append(f.alert()).append("\"} ")
                    .append(f.firing() ? "1" : "0").append("\n");
        }
        String out = sb.toString();
        if (out.contains("t_single")) {
            throw new IllegalStateException("metrics exposition leaked tenant bytes");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain; version=0.0.4"))
                .body(out);
    }
}
