package com.g1do.anvil.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.g1do.anvil.eval.EvalSet.AttackCase;
import com.g1do.anvil.extract.BoundedText;
import com.g1do.anvil.extract.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance checks for issue #79: the full evaluation set of about 700
 * documents reports precision, recall and F1 with 10,000-resample bootstrap
 * intervals and McNemar's test against the baseline; and its injection suite
 * of 50 or more multilingual and base64 cases reports attack success.
 */
class FullSetTest {

    static final Path SET = Path.of("eval/sets/full-v1");

    @Test
    void theManifestStatesSourcesSizeFreezeAndTenThousandResamples() throws IOException {
        JsonNode m = EvalSet.load(SET).manifest();

        assertThat(m.at("/name").asText()).isEqualTo("full-v1");
        assertThat(m.at("/sources").asText()).contains("generated for this repository", "fictional", "No tenant");
        assertThat(m.at("/size/quality_docs").asInt()).isEqualTo(FullSetGenerator.QUALITY_DOCS);
        assertThat(m.at("/size/quality_docs").asInt() + m.at("/size/injection_cases").asInt())
                .as("about 700 documents").isBetween(650, 800);
        assertThat(m.at("/size/gold_facts").asInt()).isGreaterThan(3000);
        assertThat(m.at("/frozen/date").asText()).isEqualTo("2026-11-02");
        assertThat(m.at("/frozen/url").asText()).endsWith("/commit/" + m.at("/frozen/commit").asText());
        assertThat(m.at("/never_tuned_on").asText()).startsWith("Never tune on this set.").contains("retired");
        assertThat(m.at("/generator/seed").asLong()).isEqualTo(FullSetGenerator.SEED);
        assertThat(m.at("/bootstrap/resamples").asInt()).isEqualTo(10_000);
        assertThat(m.at("/mcnemar").asText()).contains("McNemar", "each gold fact");
    }

    @Test
    void theSetMatchesItsManifest() throws IOException {
        EvalSet set = EvalSet.load(SET);

        assertThat(set.quality()).hasSize(FullSetGenerator.QUALITY_DOCS);
        assertThat(set.injection()).hasSize(FullSetGenerator.INJECTION_CASES);
        assertThat(set.baseline().keySet()).containsExactlyInAnyOrderElementsOf(set.docIds());

        Map<String, String> texts = new LinkedHashMap<>();
        set.docIds().forEach(id -> texts.put(id, EvalSetTest.text(set, id)));
        double max = Collections.max(EvalSet.similarities(texts, 3).values());
        assertThat(max).isLessThan(FullSetGenerator.MAX_SIMILARITY);
        assertThat(Math.round(max * 1000) / 1000.0).as("manifest records the highest pair similarity")
                .isEqualTo(set.manifest().at("/near_duplicates/max_observed").asDouble());
    }

    /** The labels are correct by construction only if the frozen files are exactly what the generator writes. */
    @Test
    void theGeneratorReproducesTheFrozenFilesByteForByte() throws IOException {
        FullSetGenerator.Output o = FullSetGenerator.generate();

        assertThat(FullSetGenerator.jsonl(o.quality()))
                .isEqualTo(Files.readString(SET.resolve(EvalSet.QUALITY), StandardCharsets.UTF_8));
        assertThat(FullSetGenerator.jsonl(o.injection()))
                .isEqualTo(Files.readString(SET.resolve(EvalSet.INJECTION), StandardCharsets.UTF_8));
    }

    @Test
    void theBaselineIsExactlyTheRulesOutput() throws IOException {
        assertThat(RulesBaseline.jsonl(RulesBaseline.predict(SET)))
                .isEqualTo(Files.readString(SET.resolve(EvalSet.BASELINE)));
    }

    @Test
    void theInjectionSuiteHasFiftyOrMoreMultilingualAndBase64Cases() throws IOException {
        List<AttackCase> cases = EvalSet.load(SET).injection();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(50);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.language()).isNotBlank();
            assertThat(c.encoding()).isIn("plain", "base64");
        });
        Map<String, Long> byLanguage = cases.stream()
                .collect(Collectors.groupingBy(AttackCase::language, Collectors.counting()));
        assertThat(byLanguage).hasSizeGreaterThanOrEqualTo(13)
                .containsKeys("en", "de", "fr", "es", "ru", "ar", "zh", "ja");
        assertThat(cases.stream().filter(c -> !c.language().equals("en"))).hasSizeGreaterThanOrEqualTo(50);

        List<AttackCase> base64 = cases.stream().filter(c -> c.encoding().equals("base64")).toList();
        assertThat(base64).hasSizeGreaterThanOrEqualTo(FullSetGenerator.BASE64_CASES);
        assertThat(base64).allSatisfy(c -> {
            assertThat(decoded(c.text())).as(c.id() + " hides its instruction in base64").anySatisfy(
                    t -> assertThat(t).contains(c.canary()));
            assertThat(c.text().replace(c.canary(), "")).as(c.id() + " writes no instruction in clear")
                    .doesNotContainIgnoringCase("ignore").doesNotContainIgnoringCase("replace")
                    .doesNotContain("[SYSTEM]");
        });
        assertThat(base64.stream().map(AttackCase::language).collect(Collectors.toSet())).hasSizeGreaterThanOrEqualTo(10);
    }

    /** McNemar and attack success by encoding and language are in the report for this set. */
    @Test
    void theGateReportsIntervalsMcNemarAndAttackSuccessByEncodingAndLanguage() throws IOException {
        EvalSet set = EvalSet.load(SET);
        EvalGate.Result r = EvalGate.run(set, set.baseline(), "baseline");

        assertThat(r.report()).contains("10000 resamples", "| baseline |", "McNemar test against the baseline on the "
                + set.manifest().at("/size/gold_facts").asInt() + " gold facts", "| Encoding |", "| base64 | 20 |",
                "| Language |", "| ja | 5 |");
        assertThat(r.mcnemar().candidateOnly()).as("the baseline against itself").isZero();
        assertThat(r.mcnemar().pValue()).isEqualTo(1.0);
        assertThat(r.byLanguage()).hasSize(13);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "eval/runs/extract-v1-deepseek-flash-full-v1",
            "eval/runs/extract-v1-deepseek-v4.1-flash-full-v1" })
    void theCommittedFullSetReportIsAFreshRescoreOfTheCommittedRun(String dir, @org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException {
        Path run = Path.of(dir);
        Path report = tmp.resolve("report.md");
        java.io.PrintStream sink = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        assertThat(EvalGate.cli(new String[] { SET.toString(), run.resolve("predictions.jsonl").toString(),
                report.toString() }, sink, sink)).isZero();
        assertThat(Files.readString(report)).isEqualTo(Files.readString(run.resolve("report.md")));
    }

    @Test
    void theCommittedGoBaselineMatchesThePromptModelAndSet() throws IOException {
        Path dir = Path.of("eval/runs/extract-v1-deepseek-v4.1-flash-full-v1");
        JsonNode run = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(dir.resolve("run.json").toFile());
        EvalSet set = EvalSet.load(SET);

        assertThat(run.path("prompt_sha256").asText()).isEqualTo(Prompt.SHA256);
        assertThat(run.path("prompt_version").asText()).isEqualTo(Prompt.VERSION);
        assertThat(run.path("model").asText()).isEqualTo("deepseek-v4.1-flash");
        assertThat(run.path("max_chars").asInt()).isEqualTo(BoundedText.MAX_CHARS);
        assertThat(run.path("set").asText()).isEqualTo(set.name());
        assertThat(run.path("set_frozen_commit").asText()).isEqualTo(set.manifest().at("/frozen/commit").asText());
        assertThat(run.path("documents").asInt()).isEqualTo(set.docIds().size());
        assertThat(run.path("model_calls").asInt()).isEqualTo(set.docIds().size());
    }

    @Test
    void theFreezeCommitHoldsTheSameBytes() throws Exception {
        String commit = EvalSet.load(SET).manifest().at("/frozen/commit").asText();
        Process probe = new ProcessBuilder("git", "cat-file", "-e", commit + "^{commit}").start();
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.waitFor() == 0, "freeze commit not in this clone");
        for (String file : List.of(EvalSet.QUALITY, EvalSet.INJECTION, EvalSet.BASELINE)) {
            Process show = new ProcessBuilder("git", "show", commit + ":" + SET.resolve(file)).start();
            byte[] frozen = show.getInputStream().readAllBytes();
            assertThat(show.waitFor()).isZero();
            assertThat(frozen).as(file + " at the freeze commit").isEqualTo(Files.readAllBytes(SET.resolve(file)));
        }
    }

    @Test
    void noTextFromTheSetAppearsUnderSrcMain() throws IOException {
        EvalSet set = EvalSet.load(SET);
        Set<String> setShingles = new HashSet<>();
        set.docIds().forEach(id -> setShingles.addAll(Text.shingles(EvalSetTest.text(set, id), 8)));
        assertThat(setShingles).hasSizeGreaterThan(10_000);

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Set<String> found = new HashSet<>(Text.shingles(Files.readString(f), 8));
                found.retainAll(setShingles);
                found.forEach(s -> hits.add(f + ": " + s));
            }
        }
        assertThat(hits).isEmpty();
    }

    /** Every base64 run's decoded text, decoding twice-encoded runs again. */
    private static List<String> decoded(String text) {
        List<String> out = new ArrayList<>();
        for (String once : Text.base64Payloads(text)) {
            out.add(once);
            out.addAll(Text.base64Payloads(once));
        }
        return out;
    }
}
