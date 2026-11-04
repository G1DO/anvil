package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #94: the compose file and README describe only the
 * local stack, never a production deployment, and both say it runs real Postgres.
 */
class StackClaimsTest {

    private static final Path COMPOSE = Path.of("docker-compose.yml");
    private static final Path README = Path.of("README.md");

    @Test
    void composeAndReadmeDoNotClaimProductionOrDeployment() throws Exception {
        for (Path path : List.of(COMPOSE, README)) {
            String text = Files.readString(path).toLowerCase(Locale.ROOT);

            assertThat(text).as("%s claims no production or deployment", path)
                    .doesNotContain("production")
                    .doesNotContain("deployed");
        }
    }

    @Test
    void composeAndReadmeSayTheStackRunsRealPostgres() throws Exception {
        for (Path path : List.of(COMPOSE, README)) {
            String text = Files.readString(path).toLowerCase(Locale.ROOT);

            assertThat(text).as("%s says the stack runs real Postgres", path)
                    .contains("real postgres");
        }
    }
}
