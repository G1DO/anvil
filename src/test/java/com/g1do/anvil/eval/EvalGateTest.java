package com.g1do.anvil.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.g1do.anvil.eval.EvalGate.Failure;
import com.g1do.anvil.eval.EvalGate.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance checks for issue #36: the gate passes on the frozen baseline and
 * fails on two seeded fixtures, one whose quality-difference interval falls
 * below 0 and one whose attack success rate rises; it checks every citation;
 * and CI runs it.
 */
class EvalGateTest {

    private static final Path SET = EvalSetTest.SET;

    @Test
    void theBaselinePasses() throws IOException {
        Result r = gate(SET.resolve(EvalSet.BASELINE));

        assertThat(r.failures()).isEmpty();
        assertThat(r.quality().deltaF1Interval().hi()).isZero();
        assertThat(r.candidateSuccesses()).isEqualTo(r.baselineSuccesses()).isEqualTo(3);
        assertThat(r.unsupported()).isEmpty();
        assertThat(r.report()).contains("Gate: PASS", "| baseline | 0.910 [0.867, 0.953] | 0.626 [0.563, 0.683] | 0.742");
    }

    @Test
    void theQualityRegressionFixtureFails() throws IOException {
        Result r = gate(Path.of("eval/fixtures/quality-regress.jsonl"));

        assertThat(r.failures()).containsExactly(Failure.QUALITY_REGRESSED);
        assertThat(r.quality().deltaF1Interval().hi()).as("the whole interval is below 0").isNegative();
        assertThat(r.quality().candidate().point().recall()).isLessThan(r.quality().baseline().point().recall());
        assertThat(r.report()).contains("Gate: FAIL", "below 0. FAIL");
    }

    @Test
    void theAttackSuccessFixtureFails() throws IOException {
        Result r = gate(Path.of("eval/fixtures/attack-rise.jsonl"));

        assertThat(r.failures()).containsExactly(Failure.ATTACK_SUCCESS_ROSE);
        assertThat(r.candidateAsr()).isGreaterThan(r.baselineAsr());
        assertThat(r.newlySucceeded()).containsExactly("i04");
        assertThat(r.quality().deltaF1Interval().hi()).isZero();
        assertThat(r.report()).contains("Gate: FAIL", "The rate rose. FAIL");
    }

    @Test
    void aCitationNotInItsDocumentFailsTheGate() throws IOException {
        EvalSet set = EvalSet.load(SET);
        Map<String, Prediction> candidate = new LinkedHashMap<>(set.baseline());
        Prediction q01 = candidate.get("q01");
        List<Fact> facts = new ArrayList<>(q01.facts());
        Fact first = facts.getFirst();
        facts.set(0, new Fact(first.field(), first.value(), first.citation().toLowerCase(java.util.Locale.ROOT)));
        candidate.put("q01", new Prediction("q01", facts));

        Result r = EvalGate.run(set, candidate, "one re-cased citation");
        assertThat(r.failures()).contains(Failure.CITATION_NOT_IN_SOURCE);
        assertThat(r.unsupported()).singleElement().satisfies(u -> assertThat(u.doc()).isEqualTo("q01"));
    }

    @Test
    void aRegressionTheIntervalCantSeparateFromZeroPasses() throws IOException {
        EvalSet set = EvalSet.load(SET);
        Map<String, Prediction> candidate = new LinkedHashMap<>(set.baseline());
        candidate.put("q01", new Prediction("q01", List.of()));

        Result r = EvalGate.run(set, candidate, "one document emptied");
        assertThat(r.quality().deltaF1()).isNegative();
        assertThat(r.quality().deltaF1Interval().hi()).as("record 0021: only a whole interval below 0 fails")
                .isGreaterThanOrEqualTo(0);
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void predictionsMustCoverEveryDocumentExactlyOnce(@TempDir Path tmp) throws IOException {
        EvalSet set = EvalSet.load(SET);
        List<String> lines = Files.readAllLines(SET.resolve(EvalSet.BASELINE));
        Path missing = tmp.resolve("missing.jsonl");
        Files.write(missing, lines.subList(1, lines.size()));
        Path repeated = tmp.resolve("repeated.jsonl");
        List<String> twice = new ArrayList<>(lines);
        twice.add(lines.getFirst());
        Files.write(repeated, twice);

        assertThatThrownBy(() -> EvalSet.readPredictions(missing, new HashSet<>(set.docIds())))
                .hasMessageContaining("no prediction for document q01");
        assertThatThrownBy(() -> EvalSet.readPredictions(repeated, new HashSet<>(set.docIds())))
                .hasMessageContaining("document q01 appears twice");
    }

    @Test
    void ciRunsTheEvalGateAndTheFreezeCheck() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/gate.yml"));
        String taskfile = Files.readString(Path.of("Taskfile.yml"));

        assertThat(workflow).contains("  eval:\n").contains("      - run: task eval\n")
                .containsPattern("needs: \\[[^]]*\\beval\\b[^]]*]");
        assertThat(taskfile).contains("com.g1do.anvil.eval.EvalGate eval/sets/pilot-v1")
                .contains("default \"eval/sets/pilot-v1/baseline.jsonl\"")
                .contains("      - task: test:contract:eval\n")
                .contains("      - task eval\n");
    }

    private static Result gate(Path candidate) throws IOException {
        EvalSet set = EvalSet.load(SET);
        return EvalGate.run(set, EvalSet.readPredictions(candidate, new HashSet<>(set.docIds())), candidate.toString());
    }
}
