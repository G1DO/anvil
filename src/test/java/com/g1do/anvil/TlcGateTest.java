package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #103: the gate runs TLC on docs/faults/fencing.tla, TLC is
 * pinned to an exact version with a SHA-256, and README guarantee 4 links the run. Whether
 * TLC passes is shown by the gate's tla job, and the states checked are recorded in the PR.
 */
class TlcGateTest {

    private static final Path TASKFILE = Path.of("Taskfile.yml");
    private static final Path WORKFLOW = Path.of(".github/workflows/gate.yml");
    private static final Path README = Path.of("README.md");

    private static final Pattern TLC_VERSION = Pattern.compile("(?m)^  TLC_VERSION: (\\d+\\.\\d+\\.\\d+)$");
    private static final Pattern TLC_SHA256 = Pattern.compile("(?m)^  TLC_SHA256: ([0-9a-f]{64})$");
    private static final Pattern TASK_HEADER = Pattern.compile("(?m)^  ([\\w:.-]+):$");
    private static final Pattern NEEDS = Pattern.compile("needs: \\[(.*?)]");

    @Test
    void tlcIsPinnedToAnExactVersionAndChecksum() throws Exception {
        String taskfile = Files.readString(TASKFILE);

        assertThat(TLC_VERSION.matcher(taskfile).find()).as("TLC_VERSION is an exact x.y.z version").isTrue();
        assertThat(TLC_SHA256.matcher(taskfile).find()).as("TLC_SHA256 is a 64-hex digest").isTrue();

        String check = blocks(taskfile, TASK_HEADER).get("check:tla");
        assertThat(check).as("check:tla task exists").isNotNull();
        assertThat(check).as("check:tla downloads the pinned release, never latest")
                .contains("releases/download/v{{.TLC_VERSION}}/tla2tools.jar")
                .doesNotContain("releases/latest");
    }

    @Test
    void checkTlaVerifiesTheJarAndRunsTlcOnTheSpecAndConfig() throws Exception {
        String check = blocks(Files.readString(TASKFILE), TASK_HEADER).get("check:tla");

        assertThat(check).as("check:tla verifies the jar against TLC_SHA256 on every run")
                .contains("sha256sum -c");
        assertThat(check).as("check:tla runs TLC on the spec with its config")
                .contains("tlc2.TLC")
                .contains("-config docs/faults/fencing.cfg")
                .contains("docs/faults/fencing.tla");
    }

    @Test
    void gateRunsCheckTlaInItsOwnJobAndRequiresIt() throws Exception {
        Map<String, String> jobs = blocks(Files.readString(WORKFLOW), TASK_HEADER);

        assertThat(jobs).as("gate.yml has a tla job").containsKey("tla");
        assertThat(jobs.get("tla")).as("tla job runs task check:tla").contains("- run: task check:tla");

        Matcher needs = NEEDS.matcher(jobs.get("gate"));
        assertThat(needs.find()).as("gate job declares its needs").isTrue();
        assertThat(List.of(needs.group(1).split(",\\s*"))).as("gate requires the tla job").contains("tla");
    }

    @Test
    void verifyRunsCheckTla() throws Exception {
        String verify = blocks(Files.readString(TASKFILE), TASK_HEADER).get("verify");

        assertThat(verify).as("verify, the local gate, runs check:tla").contains("- task check:tla");
    }

    @Test
    void readmeGuaranteeFourLinksTheTlcRun() throws Exception {
        String readme = Files.readString(README);

        int start = readme.indexOf("\n4. **Fencing holds");
        assertThat(start).as("README guarantee 4").isGreaterThanOrEqualTo(0);
        int end = readme.indexOf("\n5. ", start);
        assertThat(end).as("guarantee 4 ends before guarantee 5").isGreaterThan(start);
        String guarantee = readme.substring(start, end);

        assertThat(guarantee).as("guarantee 4 names TLC").contains("TLC");
        assertThat(guarantee).as("guarantee 4 links the gate workflow that runs TLC")
                .contains("(.github/workflows/gate.yml)");
        assertThat(guarantee).as("guarantee 4 links the gate's runs")
                .contains("https://github.com/G1DO/anvil/actions/workflows/gate.yml");
    }

    /** Splits a Taskfile or workflow at its two-space-indented headers, keyed by header name. */
    private static Map<String, String> blocks(String text, Pattern header) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher matcher = header.matcher(text);
        String name = null;
        int bodyStart = -1;
        while (matcher.find()) {
            if (name != null) {
                blocks.put(name, text.substring(bodyStart, matcher.start()));
            }
            name = matcher.group(1);
            bodyStart = matcher.end();
        }
        if (name != null) {
            blocks.put(name, text.substring(bodyStart));
        }
        return blocks;
    }
}
