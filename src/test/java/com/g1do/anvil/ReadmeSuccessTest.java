package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #98: the README has a Success section, and each
 * measure in it links to an existing test that shows it.
 */
class ReadmeSuccessTest {

    private static final Pattern TEST_LINK = Pattern.compile("\\]\\((src/test/java/[^)\\s]+)\\)");

    @Test
    void successSectionListsMeasuresEachLinkedToAnExistingTest() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("README.md"));

        int start = lines.indexOf("## Success");
        assertThat(start).as("README has a '## Success' section").isGreaterThanOrEqualTo(0);

        List<String> section = new ArrayList<>();
        for (String line : lines.subList(start + 1, lines.size())) {
            if (line.startsWith("## ")) {
                break;
            }
            section.add(line);
        }

        List<String> measures = section.stream().filter(line -> line.trim().startsWith("- ")).toList();
        assertThat(measures).as("Success lists at least one measure").isNotEmpty();

        for (String measure : measures) {
            Matcher link = TEST_LINK.matcher(measure);
            assertThat(link.find()).as("measure links to its test: %s", measure).isTrue();
            do {
                String target = link.group(1);
                assertThat(Path.of(target)).as("linked test exists: %s", target).isRegularFile();
            } while (link.find());
        }
    }
}
