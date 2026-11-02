package com.g1do.anvil.extract;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.g1do.anvil.eval.EvalSet;
import com.g1do.anvil.eval.EvalSet.AttackCase;
import com.g1do.anvil.eval.EvalSet.QualityDoc;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.Prediction;
import com.g1do.anvil.eval.Scoring;
import com.g1do.anvil.extract.Extractor.Outcome;
import com.g1do.anvil.extract.Extractor.Quarantined;

/**
 * Runs the extraction path over a frozen eval set and writes its predictions
 * for {@code task eval} (#37, record 0022).
 *
 * <p>Each document goes through {@link Extractor#extract}, the same call the
 * service makes, as {@code text/plain}. Only accepted facts are written, so
 * the predictions are exactly what would be indexed. {@code run.json} records
 * the model, the prompt hash, quarantine counts by reason, latency, and which
 * injection cases succeeded. Per-document quality scores are left out, so the
 * run can't be used to tune the prompt (record 0021).
 *
 * <p>Usage: {@code ExtractHarness <set dir> <out dir>}. The model settings come
 * from {@code ANVIL_MODEL_API_KEY}, {@code ANVIL_MODEL_BASE_URL},
 * {@code ANVIL_MODEL} and {@code ANVIL_MODEL_AUTH}. Exits 0 when every document
 * was processed, 2 on bad input or when the model can't be asked; nothing is
 * written then.
 */
public final class ExtractHarness {

    static final String DEFAULT_BASE_URL = "https://opencode.ai/inference/go/anthropic";
    static final String DEFAULT_MODEL = "claude-haiku-5-5";
    static final String DEFAULT_SESSION_HEADER = "x-opencode-session";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractHarness() {
    }

    /** One run's predictions and its summary. */
    public record Run(List<Prediction> predictions, Map<String, Object> summary) {
    }

    public static Run run(EvalSet set, Extractor extractor) {
        List<Prediction> predictions = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        List<Long> latencies = new ArrayList<>();
        int calls = 0;
        int truncated = 0;
        int wholeDocument = 0;
        Instant started = Instant.now();

        List<String[]> docs = new ArrayList<>();
        set.quality().forEach(d -> docs.add(new String[] { d.id(), d.text() }));
        set.injection().forEach(c -> docs.add(new String[] { c.id(), c.text() }));
        Map<String, List<Fact>> byDoc = new LinkedHashMap<>();
        for (String[] d : docs) {
            long t0 = System.nanoTime();
            Outcome o = extractor.extract(d[1], "text/plain");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (o.modelCalled()) {
                calls++;
                latencies.add(ms);
            }
            truncated += o.truncated() ? 1 : 0;
            if (o.status() == Extractor.Status.QUARANTINED) {
                wholeDocument++;
            }
            for (Quarantined q : o.quarantined()) {
                reasons.merge(q.reason().name(), 1, Integer::sum);
            }
            byDoc.put(d[0], o.accepted());
            predictions.add(new Prediction(d[0], o.accepted()));
        }

        List<String> succeeded = new ArrayList<>();
        for (AttackCase c : set.injection()) {
            if (Scoring.attackSucceeded(c, byDoc.get(c.id()))) {
                succeeded.add(c.id());
            }
        }
        int facts = 0;
        for (QualityDoc d : set.quality()) {
            facts += byDoc.get(d.id()).size();
        }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("set", set.name());
        s.put("set_frozen_commit", set.manifest().at("/frozen/commit").asText());
        s.put("model", extractor.modelId());
        s.put("prompt_version", Prompt.VERSION);
        s.put("prompt_sha256", Prompt.SHA256);
        s.put("max_chars", BoundedText.MAX_CHARS);
        s.put("started_at", started.truncatedTo(ChronoUnit.SECONDS).toString());
        s.put("duration_s", Duration.between(started, Instant.now()).toSeconds());
        s.put("documents", docs.size());
        s.put("model_calls", calls);
        s.put("truncated", truncated);
        s.put("quality_facts_indexed", facts);
        s.put("documents_quarantined", wholeDocument);
        s.put("quarantined_by_reason", reasons);
        s.put("latency_ms", latency(latencies));
        Map<String, Object> attacks = new LinkedHashMap<>();
        attacks.put("cases", set.injection().size());
        attacks.put("succeeded", succeeded.size());
        attacks.put("succeeded_ids", succeeded);
        s.put("injection", attacks);
        return new Run(predictions, s);
    }

    static Map<String, Long> latency(List<Long> ms) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (ms.isEmpty()) {
            return out;
        }
        List<Long> sorted = ms.stream().sorted().toList();
        out.put("p50", nearestRank(sorted, 0.50));
        out.put("p95", nearestRank(sorted, 0.95));
        out.put("max", sorted.get(sorted.size() - 1));
        return out;
    }

    private static long nearestRank(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    static void write(Run run, Path out) throws IOException {
        Files.createDirectories(out);
        StringBuilder lines = new StringBuilder();
        for (Prediction p : run.predictions()) {
            lines.append(JSON.writeValueAsString(p)).append('\n');
        }
        Files.writeString(out.resolve("predictions.jsonl"), lines.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("run.json"),
                JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(run.summary()) + "\n",
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        System.exit(cli(args, System.getenv(), System.out, System.err));
    }

    static int cli(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("usage: ExtractHarness <set dir> <out dir>");
            return 2;
        }
        AnthropicExtractionModel model = new AnthropicExtractionModel(new AnthropicExtractionModel.Settings(
                env.getOrDefault("ANVIL_MODEL_BASE_URL", DEFAULT_BASE_URL),
                env.getOrDefault("ANVIL_MODEL", DEFAULT_MODEL),
                env.getOrDefault("ANVIL_MODEL_API_KEY", ""),
                AnthropicExtractionModel.Auth.valueOf(env.getOrDefault("ANVIL_MODEL_AUTH", "BEARER")),
                env.getOrDefault("ANVIL_MODEL_SESSION_HEADER", DEFAULT_SESSION_HEADER),
                Duration.ofSeconds(60), 4096));
        return cli(args, new Extractor(model), out, err);
    }

    static int cli(String[] args, Extractor extractor, PrintStream out, PrintStream err) {
        try {
            EvalSet set = EvalSet.load(Path.of(args[0]));
            Run run = run(set, extractor);
            write(run, Path.of(args[1]));
            out.println(JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(run.summary()));
            return 0;
        } catch (ModelUnavailableException e) {
            err.println("extract: " + e.getMessage());
            return 2;
        } catch (IOException | IllegalArgumentException e) {
            err.println("extract: " + e);
            return 2;
        }
    }
}
