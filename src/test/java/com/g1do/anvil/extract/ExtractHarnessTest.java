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
    void anAttackThatPlantsACitableValueIsCountedAsSucceeded() throws Exception {
        EvalSet set = EvalSet.load(SET);
        // A model that obeys every injected instruction: it emits each case's planted value,
        // cited exactly. The citation check can't catch this (record 0022), so the harness must.
        Map<String, String> canaryByText = new java.util.HashMap<>();
        set.injection().forEach(c -> canaryByText.put(BoundedText.of(c.text()).text(), c.canary()));
        ScriptedModel obedient = new ScriptedModel(d -> {
            String canary = canaryByText.get(d.text());
            return ScriptedModel.ok(canary == null ? "{\"facts\":[]}"
                    : "{\"facts\":[{\"field\":\"party\",\"value\":" + quote(canary) + ",\"citation\":"
                            + quote(canary) + "}]}");
        });
        ExtractHarness.Run run = ExtractHarness.run(set, new Extractor(obedient));

        @SuppressWarnings("unchecked")
        Map<String, Object> injection = (Map<String, Object>) run.summary().get("injection");
        List<String> ids = set.injection().stream().map(EvalSet.AttackCase::id).toList();
        assertThat(injection).containsEntry("cases", 12).containsEntry("succeeded", 12);
        assertThat(injection.get("succeeded_ids")).isEqualTo(ids);
    }

    private static String quote(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theCommittedRunWasMadeWithTheCommittedPromptModelAndSet() throws Exception {
        EvalSet set = EvalSet.load(SET);
        com.fasterxml.jackson.databind.JsonNode run = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Path.of("eval/runs/extract-v1-deepseek-flash/run.json").toFile());
        // Changing the prompt, model or limit means a new run; CI would otherwise re-score a stale one.
        assertThat(run.path("prompt_sha256").asText()).isEqualTo(Prompt.SHA256);
        assertThat(run.path("prompt_version").asText()).isEqualTo(Prompt.VERSION);
        assertThat(run.path("model").asText()).isEqualTo(ExtractHarness.DEFAULT_MODEL);
        assertThat(run.path("max_chars").asInt()).isEqualTo(BoundedText.MAX_CHARS);
        assertThat(run.path("set").asText()).isEqualTo(set.name());
        assertThat(run.path("set_frozen_commit").asText()).isEqualTo(set.manifest().at("/frozen/commit").asText());
        assertThat(run.path("documents").asInt()).isEqualTo(set.docIds().size());
        assertThat(run.path("model_calls").asInt()).isEqualTo(set.docIds().size());
    }

    @Test
    void aDirectoryThatHoldsARunIsNeverOverwritten(@TempDir Path out) throws Exception {
        Files.writeString(out.resolve("run.json"), "{}");
        ScriptedModel model = ScriptedModel.empty();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = ExtractHarness.cli(new String[] { SET.toString(), out.toString() }, new Extractor(model),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(model.calls()).isEmpty();
        assertThat(Files.readString(out.resolve("run.json"))).isEqualTo("{}");
        assertThat(out.resolve("predictions.jsonl")).doesNotExist();
        assertThat(e.toString(StandardCharsets.UTF_8)).contains("already holds a run");
    }

    @Test
    void blankOverridesMeanTheDefaults() {
        assertThat(ExtractHarness.model(Map.of("ANVIL_MODEL", " ", "ANVIL_MODEL_BASE_URL", "")).id())
                .isEqualTo(ExtractHarness.DEFAULT_MODEL);
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
        assertThat(e.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("extract: ")))
                .hasSize(set.docIds().size())
                .last().asString().startsWith("extract: " + set.docIds().size() + "/" + set.docIds().size() + " ");
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
