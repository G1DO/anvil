package com.g1do.anvil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/file-issue.sh}, which the scheduled {@code faults} and
 * {@code eval-nightly} workflows use to report a failed run (#79): it opens
 * one issue, and while that issue is open, later failures with the same
 * title comment on it instead of opening another. Run against a fake
 * {@code gh} that records its arguments.
 */
class FileIssueScriptTest {

    private static final Path SCRIPT = Path.of("scripts/file-issue.sh").toAbsolutePath();

    @Test
    void withNoOpenIssueItOpensOne(@TempDir Path tmp) throws Exception {
        List<String> calls = run(tmp, "[]");

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith("issue list --repo G1DO/anvil --state open");
        assertThat(calls.get(1)).isEqualTo("issue create --repo G1DO/anvil --title Nightly live eval failed --body-file "
                + tmp.resolve("body.md"));
    }

    @Test
    void whileOneIsOpenWithTheSameTitleItCommentsOnItInstead(@TempDir Path tmp) throws Exception {
        List<String> calls = run(tmp, "[{\"number\": 140, \"title\": \"Nightly live eval failed\"},"
                + " {\"number\": 131, \"title\": \"Nightly live eval failed\"}]");

        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).as("the oldest open one").isEqualTo("issue comment 131 --repo G1DO/anvil --body-file "
                + tmp.resolve("body.md"));
        assertThat(calls).noneMatch(c -> c.startsWith("issue create"));
    }

    @Test
    void anOpenIssueWithADifferentTitleDoesntCount(@TempDir Path tmp) throws Exception {
        List<String> calls = run(tmp, "[{\"number\": 7, \"title\": \"Nightly live eval failed again\"}]");

        assertThat(calls.get(1)).startsWith("issue create ");
    }

    /** Runs the script with a fake gh whose {@code issue list} prints {@code openIssues}; returns gh's calls. */
    private static List<String> run(Path tmp, String openIssues) throws IOException, InterruptedException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path log = tmp.resolve("gh.log");
        Files.writeString(tmp.resolve("open.json"), openIssues);
        Path gh = bin.resolve("gh");
        Files.writeString(gh, "#!/usr/bin/env bash\n"
                + "echo \"$*\" >> '" + log + "'\n"
                + "if [ \"$1 $2\" = \"issue list\" ]; then cat '" + tmp.resolve("open.json") + "'; fi\n");
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(tmp.resolve("body.md"), "report");

        ProcessBuilder pb = new ProcessBuilder("bash", SCRIPT.toString(), "Nightly live eval failed",
                tmp.resolve("body.md").toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().remove("GITHUB_REPOSITORY");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
        return Files.readAllLines(log);
    }
}
