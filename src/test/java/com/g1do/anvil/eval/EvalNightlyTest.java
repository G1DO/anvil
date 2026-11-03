package com.g1do.anvil.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #79: the full evaluation runs nightly against the
 * live model, gated as in CI, and a failed run opens an issue. The runs
 * themselves are on the {@code eval-nightly} workflow's page.
 */
class EvalNightlyTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/eval-nightly.yml");
    private static final Pattern PINNED = Pattern.compile("[\\w.-]+/[\\w.-]+@[0-9a-f]{40}");

    @Test
    void runsEveryNightInTheModelsOffPeakWindowAndOnDemandButNeverOnAPullRequest() throws Exception {
        Map<String, Object> on = map(workflow().get(true)); // YAML 1.1 reads the key `on` as true

        List<String> crons = list(on.get("schedule")).stream().map(e -> (String) map(e).get("cron")).toList();
        assertThat(crons).hasSize(1);
        String[] cron = crons.get(0).split(" ");
        assertThat(String.join(" ", cron[2], cron[3], cron[4])).as("every day").isEqualTo("* * *");
        int hour = Integer.parseInt(cron[1]);
        // DeepSeek charges peak rates 01:00-04:00 and 06:00-10:00 UTC (record 0023).
        assertThat(hour).as("off-peak start hour").isNotIn(1, 2, 3, 6, 7, 8, 9);
        assertThat(on).containsKey("workflow_dispatch").doesNotContainKeys("pull_request", "pull_request_target",
                "push");
    }

    @Test
    void theLiveJobExtractsTheFullSetWithTheModelKeyGivenOnlyToThatStep() throws Exception {
        Map<String, Object> live = job("live");

        assertThat(live).doesNotContainKey("env");
        assertThat(live).doesNotContainKey("permissions");
        List<Object> steps = list(live.get("steps"));
        List<Object> withKey = steps.stream().filter(s -> map(s).containsKey("env")).toList();
        assertThat(withKey).hasSize(1);
        Map<String, Object> step = map(withKey.get(0));
        assertThat(step.get("run")).isEqualTo("task eval:nightly");
        assertThat(map(step.get("env"))).containsExactly(
                Map.entry("ANVIL_MODEL_API_KEY", "${{ secrets.ANVIL_MODEL_API_KEY }}"));
        assertThat(steps).anySatisfy(s -> assertThat(String.valueOf(map(s).get("uses")))
                .startsWith("actions/upload-artifact@"));

        String task = task("eval:nightly");
        assertThat(task).as("no key fails the run instead of passing unscored")
                .contains("if [ -z \"${ANVIL_MODEL_API_KEY:-}\" ]; then").contains("exit 1")
                .contains("task: eval:extract").contains("EVAL_SET: eval/sets/full-v1")
                .contains("task: eval:full").contains("EVAL_CANDIDATE: target/eval/nightly/predictions.jsonl")
                .as("held to the committed live run, not only the baseline")
                .contains("EVAL_REFERENCE: eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl");
        assertThat(task("eval:full")).contains("{{if .REFERENCE}}\"{{.REFERENCE}}\"{{end}}");
        assertThat(task("eval:full")).contains("com.g1do.anvil.eval.EvalGate eval/sets/full-v1");
    }

    @Test
    void aFailedRunOpensAnIssueFromAJobThatRunsNoBuild() throws Exception {
        Map<String, Object> report = job("report");

        assertThat(list(report.get("needs"))).containsExactly("live");
        assertThat(report.get("if")).isEqualTo("${{ failure() }}");
        assertThat(map(report.get("permissions"))).containsEntry("issues", "write");
        assertThat(list(report.get("steps"))).anySatisfy(s -> assertThat(map(s).get("run")).isEqualTo("task eval:issue"))
                .noneSatisfy(s -> assertThat(String.valueOf(map(s).get("run"))).contains("mvnw"));
        assertThat(map(workflow().get("permissions"))).containsExactly(Map.entry("contents", "read"));
        assertThat(task("eval:issue")).contains("scripts/file-issue.sh \"Nightly live eval failed\" \"$body\"")
                .contains("target/eval/nightly/report.md");
    }

    @Test
    void everyActionIsPinnedToACommit() throws Exception {
        for (String name : List.of("live", "report")) {
            for (Object s : list(job(name).get("steps"))) {
                Object uses = map(s).get("uses");
                if (uses != null) {
                    assertThat((String) uses).matches(PINNED);
                }
            }
        }
    }

    @Test
    void theGateScoresTheCommittedFullSetRunToo() throws Exception {
        String gate = Files.readString(Path.of(".github/workflows/gate.yml"));
        assertThat(gate).contains("      - run: task eval\n      - run: task eval:full\n");
        assertThat(task("verify")).contains("      - task eval\n      - task eval:full\n");
        assertThat(task("eval:full")).contains("eval/runs/extract-v1-deepseek-flash-full-v1/predictions.jsonl");
    }

    private static Map<String, Object> workflow() throws Exception {
        return map(new Yaml().load(Files.readString(WORKFLOW)));
    }

    private static Map<String, Object> job(String name) throws Exception {
        return map(map(workflow().get("jobs")).get(name));
    }

    /** The task's block in Taskfile.yml, from its key to the next task. */
    private static String task(String name) throws Exception {
        String taskfile = Files.readString(Path.of("Taskfile.yml"));
        int start = taskfile.indexOf("\n  " + name + ":\n");
        assertThat(start).as("task " + name).isNotNegative();
        int end = taskfile.indexOf("\n\n  ", start + 1);
        return taskfile.substring(start, end < 0 ? taskfile.length() : end);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }
}
