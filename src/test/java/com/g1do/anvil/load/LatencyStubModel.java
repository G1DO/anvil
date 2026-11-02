package com.g1do.anvil.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.RulesBaseline;
import com.g1do.anvil.extract.BoundedText;
import com.g1do.anvil.extract.ExtractionModel;
import com.g1do.anvil.extract.ModelReply;

/**
 * The model the load runs use in place of DeepSeek (#79): no network and no
 * spend, but the live model's recorded latency. Each call holds its thread
 * for a time drawn from a log-normal distribution fitted to the cost run's
 * model-call latency (median {@value #MEDIAN_MS} ms, p95 {@value #P95_MS} ms,
 * record 0023), capped at {@value #MAX_MS} ms, and replies with the frozen
 * rules' facts in the model's JSON form, so the service checks and stores
 * them as it would the model's.
 */
public final class LatencyStubModel implements ExtractionModel {

    static final double MEDIAN_MS = 1142;
    static final double P95_MS = 1497;
    static final double MAX_MS = 3000;
    /** The normal 95th-percentile quantile. */
    private static final double Z95 = 1.6448536269514722;
    static final double SIGMA = Math.log(P95_MS / MEDIAN_MS) / Z95;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicLong calls = new AtomicLong();

    @Override
    public String id() {
        return "load-stub";
    }

    public long calls() {
        return calls.get();
    }

    /** One latency draw, in milliseconds. */
    static double latencyMs(double standardNormal) {
        return Math.min(MAX_MS, MEDIAN_MS * Math.exp(SIGMA * standardNormal));
    }

    @Override
    public ModelReply complete(BoundedText document) {
        calls.incrementAndGet();
        try {
            Thread.sleep((long) latencyMs(ThreadLocalRandom.current().nextGaussian()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ModelReply(reply(document.text()), "end_turn");
    }

    static String reply(String text) {
        List<Map<String, String>> facts = new ArrayList<>();
        for (Fact f : RulesBaseline.extract(text)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("field", f.field());
            m.put("value", f.value());
            m.put("citation", f.citation());
            facts.add(m);
        }
        try {
            return JSON.writeValueAsString(Map.of("facts", facts));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
