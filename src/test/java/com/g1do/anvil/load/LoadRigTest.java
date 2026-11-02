package com.g1do.anvil.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.eval.CitationCheck;
import com.g1do.anvil.extract.BoundedText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Acceptance checks for issue #79: load results at 140 rps, a 10x flood and a
 * 10,000-documents-a-day soak report p95 latency, error rate and table-bloat
 * growth; and no soak numbers are written before the GIN budget is measured
 * and met (record 0025).
 */
class LoadRigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theScenariosOffer140PerSecondTenTimesThatAndTenThousandADay() {
        assertThat(LoadRig.Scenario.of("steady").k6()).containsEntry("RATE", "140").containsEntry("DURATION", "5m");
        assertThat(LoadRig.Scenario.of("flood").k6()).containsEntry("RATE", "1400").containsKey("RECOVERY_RATE");
        assertThat(LoadRig.Scenario.of("soak").k6()).containsEntry("RATE", "10000").containsEntry("TIME_UNIT", "60m")
                .containsEntry("DURATION", "60m");
        assertThatThrownBy(() -> LoadRig.Scenario.of("burst")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LoadRig.K6).as("k6 is pinned by digest").matches("grafana/k6:[0-9.]+@sha256:[0-9a-f]{64}");
    }

    @Test
    void theSoakRefusesToRunWithoutAGinVerdict(@TempDir Path tmp) {
        assertThatThrownBy(() -> LoadRig.requireGinBudget(tmp.resolve("gin-budget.json")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("record 0025")
                .hasMessageContaining("No soak numbers are written");
    }

    @Test
    void theSoakRefusesToRunWhenTheGinBudgetIsntMet(@TempDir Path tmp) throws IOException {
        Path verdict = tmp.resolve("gin-budget.json");
        Files.writeString(verdict, "{\"within_budget\": false, \"chosen\": \"none\"}");

        assertThatThrownBy(() -> LoadRig.requireGinBudget(verdict)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over budget").hasMessageContaining("no soak numbers are claimed");

        Files.writeString(verdict, "{\"within_budget\": true, \"chosen\": \"gin_fastupdate_off\"}");
        assertThat(LoadRig.requireGinBudget(verdict).path("chosen").asText()).isEqualTo("gin_fastupdate_off");
    }

    @Test
    void theStubModelHasTheLiveModelsRecordedLatencyAndRepliesWithCitedFacts() throws IOException {
        assertThat(LatencyStubModel.latencyMs(0)).isEqualTo(LatencyStubModel.MEDIAN_MS);
        assertThat(LatencyStubModel.latencyMs(1.6448536269514722)).isCloseTo(LatencyStubModel.P95_MS, within(0.5));
        assertThat(LatencyStubModel.latencyMs(10)).isEqualTo(LatencyStubModel.MAX_MS);

        String text = "Invoice from Halvane Freight AS, due 3 March 2026: EUR 4,250.00. Contact Ms. Ingrid Solberg.";
        JsonNode reply = JSON.readTree(new LatencyStubModel().complete(BoundedText.of(text)).text());
        assertThat(reply.path("facts")).hasSize(4);
        reply.path("facts").forEach(f -> assertThat(CitationCheck.isExactSubstring(text, f.path("citation").asText()))
                .isTrue());
    }

    @Test
    void theGinVerdictPrecedesTheSoakAndIsWithinBudget() throws IOException {
        JsonNode gin = JSON.readTree(LoadRig.GIN_VERDICT.toFile());
        JsonNode soak = JSON.readTree(Path.of("load/runs/soak/result.json").toFile());

        assertThat(gin.path("within_budget").asBoolean()).isTrue();
        assertThat(gin.path("extractions").asInt()).isEqualTo(GinBudget.EXTRACTIONS);
        assertThat(soak.path("gin_budget").path("measured_at").asText()).isEqualTo(gin.path("measured_at").asText());
        assertThat(Instant.parse(gin.path("measured_at").asText()))
                .isBefore(Instant.parse(soak.path("started_at").asText()));
    }

    /**
     * Each committed run reports p95 latency, error rate and table-bloat
     * growth. The two first runs pre-allocated fewer virtual users
     * (load/README.md).
     */
    @ParameterizedTest
    @ValueSource(strings = { "steady", "flood", "soak", "steady-300-vus", "flood-1500-vus" })
    void eachCommittedRunReportsP95ErrorRateAndTableGrowth(String dirName) throws IOException {
        Path dir = Path.of("load/runs", dirName);
        JsonNode r = JSON.readTree(dir.resolve("result.json").toFile());

        assertThat(r.path("scenario").asText()).isEqualTo(dirName.replaceAll("-.*", ""));
        assertThat(r.path("k6").asText()).isEqualTo(LoadRig.K6);
        assertThat(r.path("k6_exit").asInt()).isZero();
        JsonNode load = r.path("load");
        assertThat(load.path("extract_ms").path("p95").asLong()).isPositive();
        assertThat(load.path("error_rate").isNumber()).isTrue();
        assertThat(load.path("iterations").asLong() + load.path("dropped").asLong()).isPositive();
        for (String table : List.of("documents", "extractions", "extracted_facts", "extraction_budgets")) {
            assertThat(r.path("tables").path(table).path("bytes_after").asLong()).as(table).isPositive();
            assertThat(r.path("tables").path(table).has("dead_after_settle")).as(table).isTrue();
        }
        assertThat(Files.readString(dir.resolve("report.md"))).contains("| Error rate |", "p95", "Table growth");
        assertThat(dir.resolve("k6-summary.json")).isRegularFile();
    }

    @Test
    void theSoakOfferedTenThousandDocumentsAndTheSteadyRunHeld140PerSecond() throws IOException {
        JsonNode soak = JSON.readTree(Path.of("load/runs/soak/result.json").toFile()).path("load");
        // A constant-arrival-rate run also starts one request at time zero.
        assertThat(soak.path("iterations").asLong() + soak.path("dropped").asLong()).isBetween(10_000L, 10_001L);
        assertThat(soak.path("error_rate").asDouble()).isZero();

        JsonNode steady = JSON.readTree(Path.of("load/runs/steady/result.json").toFile()).path("load");
        assertThat(steady.path("iterations").asLong() + steady.path("dropped").asLong())
                .as("5 minutes at 140 per second").isBetween(41_900L, 42_100L);

        JsonNode flood = JSON.readTree(Path.of("load/runs/flood/result.json").toFile()).path("load");
        assertThat(flood.path("main").path("iterations").asLong() + flood.path("dropped").asLong())
                .as("60 seconds at 1,400 per second").isBetween(83_000L, 84_100L);
    }
}
