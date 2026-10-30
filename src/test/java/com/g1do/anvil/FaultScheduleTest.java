package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #78: the scheduled {@code faults} workflow runs 100
 * simulator runs nightly and the real-Postgres fault suite 5 times weekly, each from a
 * fresh seed, and opens an issue with the seed and trace when a run fails. The runs
 * themselves are on the workflow's page; {@code FenceSimTest} proves a seeded failure
 * writes its seed and trace.
 */
class FaultScheduleTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/faults.yml");
    private static final Path TASKFILE = Path.of("Taskfile.yml");
    private static final String NIGHTLY = "17 3 * * *";
    private static final String WEEKLY = "41 4 * * 0";
    private static final Pattern PINNED = Pattern.compile("[\\w.-]+/[\\w.-]+@[0-9a-f]{40}");

    @Test
    void runsOnANightlyAndAWeeklyScheduleAndOnDemand() throws Exception {
        Map<String, Object> on = map(workflow().get(true)); // YAML 1.1 reads the key `on` as true

        List<String> crons = list(on.get("schedule")).stream().map(entry -> (String) map(entry).get("cron")).toList();
        assertThat(crons).containsExactly(NIGHTLY, WEEKLY);
        assertThat(NIGHTLY).as("nightly runs every day").endsWith("* * *");
        assertThat(WEEKLY).as("weekly runs on one day of the week").matches(".* \\* \\* [0-6]");

        Map<String, Object> inputs = map(map(on.get("workflow_dispatch")).get("inputs"));
        assertThat(list(map(inputs.get("seed_break")).get("options")))
                .as("a dispatch can seed a failure on purpose").contains("none", "BROKEN_FENCE");
    }

    @Test
    void nightlyRunsTheSimulator100TimesFromTheRunsSeed() throws Exception {
        Map<String, Object> sim = job("sim");

        assertThat((String) sim.get("if")).contains("'" + NIGHTLY + "'");
        Map<String, Object> env = map(sim.get("env"));
        assertThat(env.get("FAULT_SIM_RUNS")).isEqualTo("100");
        assertThat(env.get("FAULT_SEED")).isEqualTo("${{ github.run_id }}");
        assertThat((String) env.get("FAULT_SIM_BREAK")).contains("inputs.seed_break");
        assertThat(runs(sim)).contains("task faults:sim");
        assertThat(task("faults:sim")).contains("-Dtest='FenceSimTest#scheduledRunsKeepFencing'");
        assertUploadsReportsOnFailure(sim);
    }

    @Test
    void weeklyRunsTheRealPostgresFaultSuiteFiveTimesWithDifferentSeeds() throws Exception {
        Map<String, Object> pg = job("pg");

        assertThat((String) pg.get("if")).contains("'" + WEEKLY + "'");
        Map<String, Object> strategy = map(pg.get("strategy"));
        assertThat(list(map(strategy.get("matrix")).get("run"))).containsExactly(1, 2, 3, 4, 5);
        assertThat(strategy.get("fail-fast")).as("one failed run doesn't cancel the others").isEqualTo(false);
        assertThat(map(pg.get("env")).get("FAULT_SEED")).isEqualTo("${{ github.run_id }}${{ matrix.run }}");
        assertThat(runs(pg)).contains("task faults:pg");
        assertThat(task("faults:pg")).contains("-Dtest='FaultProofTest,FaultMatrixTest'");
        assertUploadsReportsOnFailure(pg);
    }

    @Test
    void aFailedRunOpensAnIssueWithItsSeedAndTrace() throws Exception {
        Map<String, Object> report = job("report");

        assertThat(list(report.get("needs"))).containsExactlyInAnyOrder("sim", "pg");
        assertThat(report.get("if")).isEqualTo("${{ failure() }}");
        assertThat(runs(report)).contains("task faults:issue");
        String issue = task("faults:issue");
        assertThat(issue).as("the issue body is the reports, which carry seed and trace")
                .contains("cat {{.REPORTS}}/*.md")
                .contains("gh issue create")
                .contains("--body-file");
        assertThat(issue).as("the title names the seeds").contains("Seed: `").contains("seed $seeds");
        assertThat(issue).as("a run with no report still opens an issue")
                .contains("No fault case wrote a seed or trace");
    }

    @Test
    void onlyTheReportJobCanWriteIssuesAndEveryActionIsPinned() throws Exception {
        Map<String, Object> workflow = workflow();

        assertThat(map(workflow.get("permissions"))).containsExactly(Map.entry("contents", "read"));
        Map<String, Object> jobs = map(workflow.get("jobs"));
        for (Map.Entry<String, Object> job : jobs.entrySet()) {
            Object permissions = map(job.getValue()).get("permissions");
            if (job.getKey().equals("report")) {
                assertThat(map(permissions)).containsOnly(Map.entry("contents", "read"), Map.entry("issues", "write"));
            } else {
                assertThat(permissions).as("%s keeps the read-only token", job.getKey()).isNull();
            }
            for (Object step : list(map(job.getValue()).get("steps"))) {
                Object uses = map(step).get("uses");
                if (uses != null) {
                    assertThat(PINNED.matcher((String) uses).matches()).as("%s pinned to a commit SHA", uses).isTrue();
                }
            }
        }
    }

    private static void assertUploadsReportsOnFailure(Map<String, Object> job) {
        Map<String, Object> upload = list(job.get("steps")).stream().map(FaultScheduleTest::map)
                .filter(step -> String.valueOf(step.get("uses")).startsWith("actions/upload-artifact@"))
                .findFirst().orElseThrow();
        assertThat(upload.get("if")).isEqualTo("${{ failure() }}");
        assertThat(map(upload.get("with")).get("path")).isEqualTo("target/fault-runs/");
        assertThat((String) map(upload.get("with")).get("name")).startsWith("fault-runs-");
    }

    private static List<String> runs(Map<String, Object> job) {
        return list(job.get("steps")).stream().map(step -> (String) map(step).get("run"))
                .filter(run -> run != null).toList();
    }

    private static Map<String, Object> job(String name) throws Exception {
        return map(map(workflow().get("jobs")).get(name));
    }

    private static String task(String name) throws Exception {
        Map<String, Object> tasks = map(new Yaml().<Map<String, Object>>load(Files.readString(TASKFILE)).get("tasks"));
        return String.valueOf(map(tasks.get(name)));
    }

    private static Map<String, Object> workflow() throws Exception {
        return map(new Yaml().load(Files.readString(WORKFLOW)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
