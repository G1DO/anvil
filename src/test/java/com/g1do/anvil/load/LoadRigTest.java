package com.g1do.anvil.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(LoadRig.Scenario.of("steady").k6()).as("every virtual user exists before the run")
                .containsEntry("PRE_VUS", "1500").containsEntry("MAX_VUS", "1500");
        assertThat(LoadRig.Scenario.of("flood").k6()).containsEntry("PRE_VUS", "3000").containsEntry("MAX_VUS", "3000");
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
        Files.writeString(verdict, "{\"extractions\": 10000, \"within_budget\": false, \"chosen\": \"none\"}");

        assertThatThrownBy(() -> LoadRig.requireGinBudget(verdict)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over budget").hasMessageContaining("no soak numbers are claimed");

        Files.writeString(verdict, "{\"extractions\": 10000, \"within_budget\": true, \"chosen\": \"gin_fastupdate_off\"}");
        assertThat(LoadRig.requireGinBudget(verdict).path("chosen").asText()).isEqualTo("gin_fastupdate_off");
    }

    /** Review of #121: a short trial run of the harness, or a verdict naming no measured variant, isn't a soak day's verdict. */
    @Test
    void theSoakRefusesATrialVerdictOrOneThatChoseNoMeasuredVariant(@TempDir Path tmp) throws IOException {
        Path verdict = tmp.resolve("gin-budget.json");
        Files.writeString(verdict, "{\"extractions\": 200, \"within_budget\": true, \"chosen\": \"gin_fastupdate_on\"}");
        assertThatThrownBy(() -> LoadRig.requireGinBudget(verdict)).hasMessageContaining("measured 200 extractions")
                .hasMessageContaining("No soak numbers are written");

        Files.writeString(verdict, "{\"extractions\": 10000, \"within_budget\": true, \"chosen\": \"gin_other\"}");
        assertThatThrownBy(() -> LoadRig.requireGinBudget(verdict)).hasMessageContaining("isn't a measured variant");
    }

    @Test
    void aRunCanOverrideOnlySettingsItsScenarioHas() {
        LoadRig.Scenario sc = LoadRig.Scenario.of("steady").with(LoadRig.overrides(List.of("PRE_VUS=300")));
        assertThat(sc.k6()).containsEntry("PRE_VUS", "300").containsEntry("RATE", "140");
        assertThat(sc.overrides()).containsExactly(java.util.Map.entry("PRE_VUS", "300"));
        assertThatThrownBy(() -> LoadRig.Scenario.of("steady").with(LoadRig.overrides(List.of("RECOVERY_RATE=1"))))
                .hasMessageContaining("no k6 setting RECOVERY_RATE");
        assertThatThrownBy(() -> LoadRig.overrides(List.of("PRE_VUS"))).hasMessageContaining("NAME=value");
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

    /**
     * Record 0025: the committed verdict measured a full soak day and neither
     * variant met the budget, so the soak refuses to run and no soak numbers
     * exist; the residual is in the verdict.
     */
    @Test
    void overBudgetTheSoakRefusesAndNoSoakNumbersAreClaimed() throws IOException {
        JsonNode gin = JSON.readTree(LoadRig.GIN_VERDICT.toFile());

        assertThat(gin.path("extractions").asInt()).isEqualTo(GinBudget.EXTRACTIONS);
        assertThat(gin.path("within_budget").asBoolean()).isFalse();
        assertThat(gin.path("chosen").asText()).isEqualTo("none");
        for (String variant : GinBudget.VARIANTS.keySet()) {
            JsonNode checks = gin.path("variants").path(variant).path("checks");
            assertThat(checks.findValues("ok")).as(variant + " reports its residual: a failed check")
                    .anySatisfy(ok -> assertThat(ok.asBoolean()).isFalse());
        }
        assertThatThrownBy(() -> LoadRig.requireGinBudget(LoadRig.GIN_VERDICT)).hasMessageContaining("over budget")
                .hasMessageContaining("no soak numbers are claimed");
        assertThat(Path.of("load/runs/soak")).doesNotExist();
    }

    /**
     * Each committed run reports p95 latency, error rate and table-bloat
     * growth. The two first runs pre-allocated fewer virtual users
     * (load/README.md).
     */
    @ParameterizedTest
    @ValueSource(strings = { "steady", "flood", "steady-300-vus", "flood-1500-vus" })
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
        assertThat(Files.readString(dir.resolve("report.md"))).contains("| Error rate |", "p95", "Table growth",
                "Per 1,000 completed", "connection errors");
        assertThat(load.has("connection_errors") && load.has("script_errors")).isTrue();
        assertThat(dir.resolve("k6-summary.json")).isRegularFile();
    }

    @Test
    void eachRunOfferedItsLoadAndTheServerAnsweredEveryRequestItGot() throws IOException {
        for (String name : List.of("steady", "steady-300-vus")) {
            JsonNode steady = JSON.readTree(Path.of("load/runs", name, "result.json").toFile()).path("load");
            assertThat(steady.path("iterations").asLong() + steady.path("dropped").asLong())
                    .as(name + ": 5 minutes at 140 per second").isBetween(41_900L, 42_100L);
            noServerErrors(name, steady);
        }
        for (String name : List.of("flood", "flood-1500-vus")) {
            JsonNode flood = JSON.readTree(Path.of("load/runs", name, "result.json").toFile()).path("load");
            assertThat(flood.path("main").path("iterations").asLong() + flood.path("dropped").asLong())
                    .as(name + ": 60 seconds at 1,400 per second").isBetween(83_000L, 84_100L);
            assertThat(flood.path("recovery").path("extract_ms").path("p95").asLong())
                    .as(name + ": latency back near the model's own after the flood").isLessThan(2_000);
            noServerErrors(name, flood);
        }
    }

    /** Requests that weren't completed were never started by k6; none got an error, a reset or a timeout. */
    private static void noServerErrors(String name, JsonNode load) {
        for (String kind : List.of("errors", "timeouts", "connection_errors", "server_errors", "client_errors",
                "script_errors")) {
            assertThat(load.path(kind).asLong()).as(name + " " + kind).isZero();
        }
    }
}
