package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #101: decision record 0018 uses the template headings,
 * names the three dev-password locations, states they are dev-only, and records the
 * production path as decided, or as open with an owner and a revisit date.
 */
class DevPasswordDecisionTest {

    private static final Path RECORD = Path.of("docs/decisions/0018-dev-only-password-defaults.md");
    private static final Pattern HEADING = Pattern.compile("^## (.+)$", Pattern.MULTILINE);
    private static final Pattern REVISIT_BY = Pattern.compile("Revisit by: (\\d{4}-\\d{2}-\\d{2})");

    @Test
    void recordUsesTheTemplateHeadingsInOrder() throws Exception {
        assertThat(RECORD).as("decision record 0018 exists").isRegularFile();
        String text = Files.readString(RECORD);

        assertThat(text.lines().findFirst().orElse("")).as("title line").startsWith("# 0018 — ");
        assertThat(text).as("status line").containsPattern("(?m)^Status: \\S+");
        assertThat(headings(text)).as("headings, in template order")
                .containsExactly("Context", "Decision", "Consequences");
    }

    @Test
    void contextNamesTheAlternativesConsidered() throws Exception {
        assertThat(section(Files.readString(RECORD), "Context")).as("Context section")
                .contains("Alternatives considered");
    }

    @Test
    void recordNamesTheThreeLocationsAndSaysTheyAreDevOnly() throws Exception {
        String text = Files.readString(RECORD);

        assertThat(text).as("names the V2 role passwords")
                .contains("src/main/resources/db/migration/V2__tenant_rls.sql");
        assertThat(text).as("names the Compose defaults").contains("docker-compose.yml");
        assertThat(text).as("names the Taskfile default").contains("Taskfile.yml");
        assertThat(section(text, "Decision")).as("Decision says dev-only").contains("dev-only");
    }

    @Test
    void productionPathIsDecidedOrOpenWithOwnerAndRevisitDate() throws Exception {
        String decision = section(Files.readString(RECORD), "Decision");

        if (decision.contains("Production path: decided")) {
            return;
        }
        assertThat(decision).as("production path is decided or open").contains("Production path: open");
        assertThat(decision).as("open production path has an owner").containsPattern("Owner: \\S");

        Matcher revisit = REVISIT_BY.matcher(decision);
        assertThat(revisit.find()).as("open production path has a revisit date").isTrue();
        assertThat(LocalDate.parse(revisit.group(1))).as("revisit date is a real date")
                .isAfter(LocalDate.of(2026, 10, 29));
    }

    private static List<String> headings(String text) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING.matcher(text);
        while (matcher.find()) {
            headings.add(matcher.group(1).trim());
        }
        return headings;
    }

    private static String section(String text, String heading) {
        int start = text.indexOf("## " + heading + "\n");
        assertThat(start).as("## %s heading", heading).isGreaterThanOrEqualTo(0);
        int end = text.indexOf("\n## ", start + 1);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
