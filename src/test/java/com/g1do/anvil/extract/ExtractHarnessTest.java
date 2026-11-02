package com.g1do.anvil.extract;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.g1do.anvil.eval.EvalGate;
import com.g1do.anvil.eval.EvalGate.Failure;
import com.g1do.anvil.eval.EvalSet;
import com.g1do.anvil.eval.Prediction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness runs the extraction path over the frozen set (#37). These runs
 * use scripted models, so they need no network.
 */
class ExtractHarnessTest {

    static final Path SET = Path.of("eval/sets/pilot-v1");

    @Test
    void everyDocumentGoesThroughTheExtractorOnce() throws Exception {
        EvalSet set = EvalSet.load(SET);
        ScriptedModel model = ScriptedModel.empty();
        ExtractHarness.Run run = ExtractHarness.run(set, new Extractor(model));

        assertThat(model.calls()).hasSize(set.docIds().size());
        assertThat(model.calls()).allSatisfy(d -> assertThat(d.text().length()).isLessThanOrEqualTo(500));
        assertThat(run.predictions()).extracting(Prediction::doc).containsExactlyElementsOf(set.docIds());
        assertThat(run.summary()).containsEntry("model_calls", set.docIds().size())
                .containsEntry("prompt_sha256", Prompt.SHA256)
                .containsEntry("model", "scripted");
        @SuppressWarnings("unchecked")
        Map<String, Object> injection = (Map<String, Object>) run.summary().get("injection");
        assertThat(injection).containsEntry("cases", 12).containsEntry("succeeded", 0);
    }

    @Test
    void factsWithUnsupportedCitationsNeverReachThePredictions() throws Exception {
        EvalSet set = EvalSet.load(SET);
        // Every document gets one fact citing text that isn't in it.
        ScriptedModel liar = new ScriptedModel(d -> ScriptedModel.ok(
                "{\"facts\":[{\"field\":\"party\",\"value\":\"Phantom Ltd\",\"citation\":\"Phantom Ltd, never written\"}]}"));
        ExtractHarness.Run run = ExtractHarness.run(set, new Extractor(liar));

        assertThat(run.predictions()).allSatisfy(p -> assertThat(p.facts()).isEmpty());
        assertThat(run.summary().get("quarantined_by_reason"))
                .isEqualTo(Map.of("CITATION_NOT_IN_SOURCE", set.docIds().size()));

        Map<String, Prediction> byDoc = new java.util.LinkedHashMap<>();
        run.predictions().forEach(p -> byDoc.put(p.doc(), p));
        EvalGate.Result gate = EvalGate.run(set, byDoc, "liar");
        assertThat(gate.unsupported()).isEmpty();
        assertThat(gate.failures()).doesNotContain(Failure.CITATION_NOT_IN_SOURCE);
    }

    @Test
    void theCliWritesPredictionsTheGateCanRead(@TempDir Path out) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = ExtractHarness.cli(new String[] { SET.toString(), out.toString() },
                new Extractor(ScriptedModel.empty()), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        EvalSet set = EvalSet.load(SET);
        assertThat(EvalSet.readPredictions(out.resolve("predictions.jsonl"), new HashSet<>(set.docIds())))
                .hasSize(set.docIds().size());
        assertThat(Files.readString(out.resolve("run.json"))).contains("\"prompt_sha256\" : \"" + Prompt.SHA256);
    }

    @Test
    void aModelOutageWritesNothingAndExits2(@TempDir Path tmp) {
        Path out = tmp.resolve("run");
        ScriptedModel down = new ScriptedModel(d -> {
            throw new ModelUnavailableException("down");
        });
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = ExtractHarness.cli(new String[] { SET.toString(), out.toString() }, new Extractor(down),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(out).doesNotExist();
        assertThat(e.toString(StandardCharsets.UTF_8)).contains("down");

        assertThat(ExtractHarness.cli(new String[] { SET.toString() }, Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))).isEqualTo(2);
    }

    @Test
    void latencyUsesNearestRank() {
        assertThat(ExtractHarness.latency(List.of(5L, 1L, 3L, 2L, 4L)))
                .containsEntry("p50", 3L).containsEntry("p95", 5L).containsEntry("max", 5L);
        assertThat(ExtractHarness.latency(List.of())).isEmpty();
    }
}
