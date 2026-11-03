package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.extract.ExtractionService;
import com.g1do.anvil.extract.ScriptedModel;
import com.g1do.anvil.load.GinBudget;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #79 (record 0025): the full-text index on fact
 * values was measured against its budget over a full soak day, neither
 * variant met it, and so the schema has none. Each measured variant, built
 * from the statement the budget measured, answers a tenant's lookup only
 * inside that tenant, and its kill-switch, an operator dropping it, leaves
 * extraction and the facts route working, because no code depends on it.
 */
@Import({ TestcontainersConfiguration.class, GinIndexTest.Model.class })
@SpringBootTest
class GinIndexTest {

    private static final String T = "t_gin_switch";
    private static final Path VERDICT = Path.of("load/runs/gin-budget/gin-budget.json");

    @TestConfiguration(proxyBeanMethods = false)
    static class Model {

        @Bean
        @Primary
        ScriptedModel ginScriptedModel() {
            return new ScriptedModel(ExtractionCostTest::firstWord);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submit;

    @Autowired
    ExtractionService service;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO tenants (id) VALUES (?)", T);
        jdbc.update("INSERT INTO extraction_budgets (tenant_id, calls_remaining) VALUES (?, 10)", T);
    }

    /** Leaves the suite's database at the migrated schema, which has no full-text index. */
    @AfterEach
    void clean() {
        jdbc.execute("DROP INDEX IF EXISTS " + GinBudget.INDEX);
        jdbc.execute("TRUNCATE TABLE audit_events");
        jdbc.update("DELETE FROM extraction_budgets WHERE tenant_id = ?", T);
        jdbc.update("DELETE FROM extractions WHERE tenant_id = ?", T);
        for (String t : List.of("outbox", "jobs", "document_versions", "documents")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id = ?", T);
        }
        jdbc.update("DELETE FROM tenants WHERE id = ?", T);
    }

    @Test
    void overBudgetOnAFullDayNoMigrationAddsTheIndex() throws Exception {
        JsonNode verdict = verdict();
        assertThat(verdict.path("extractions").asInt()).as("a full soak day").isEqualTo(10_000);
        assertThat(verdict.path("within_budget").asBoolean()).isFalse();
        assertThat(verdict.path("chosen").asText()).isEqualTo("none");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                GinBudget.INDEX)).as("the migrated schema has no full-text index").isZero();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path f : files.toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain(GinBudget.INDEX)
                        .doesNotContainIgnoringCase("using gin");
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "off", "on" })
    void eachMeasuredIndexAnswersATenantsLookupOnlyInsideThatTenant(String fastupdate) throws Exception {
        jdbc.execute(GinBudget.createIndex(fastupdate));
        assertThat(jdbc.queryForObject("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE relname = ?",
                String.class, GinBudget.INDEX)).isEqualTo("fastupdate=" + fastupdate);

        UUID doc = submit.submit(T, "k-" + UUID.randomUUID(), "Title", "text/plain",
                "Brightmoor billed us twice this month.").docId();
        assertThat(service.extract(T, doc)).get().satisfies(r -> assertThat(r.status()).isEqualTo("ACCEPTED"));

        String lookup = "SELECT count(*) FROM extracted_facts WHERE to_tsvector('simple', value) "
                + "@@ to_tsquery('simple', 'brightmoor')";
        assertThat(asTenant(T, lookup)).isEqualTo(1);
        assertThat(asTenant("t_gin_other", lookup)).as("RLS still applies").isZero();
    }

    /** The kill-switch in record 0025, run as the operator role. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "off", "on" })
    void droppingTheIndexLeavesExtractionAndTheFactsRouteWorking(String fastupdate) throws Exception {
        jdbc.execute(GinBudget.createIndex(fastupdate));
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE anvil_migrator");
                s.execute("DROP INDEX CONCURRENTLY " + GinBudget.INDEX);
            } finally {
                try (Statement s = c.createStatement()) {
                    s.execute("RESET ROLE");
                }
            }
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                GinBudget.INDEX)).isZero();

        UUID doc = submit.submit(T, "k-" + UUID.randomUUID(), "Title", "text/plain",
                "Kestrel sent the invoice on Monday.").docId();
        assertThat(service.extract(T, doc)).get().satisfies(r -> {
            assertThat(r.status()).isEqualTo("ACCEPTED");
            assertThat(r.facts()).isEqualTo(1);
        });
        assertThat(service.facts(T, doc)).get().satisfies(f -> assertThat(f.facts()).hasSize(1));
    }

    /** What makes the kill-switch safe at any time. */
    @Test
    void noApplicationCodeDependsOnTheIndex() throws Exception {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String code = Files.readString(f);
                if (code.contains(GinBudget.INDEX) || code.contains("to_tsvector") || code.contains("to_tsquery")) {
                    hits.add(f.toString());
                }
            }
        }
        assertThat(hits).isEmpty();
    }

    private static JsonNode verdict() throws Exception {
        return new ObjectMapper().readTree(VERDICT.toFile());
    }

    private int asTenant(String tenant, String sql) {
        return jdbc.execute((ConnectionCallback<Integer>) c -> {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE anvil_app");
                s.execute("SELECT set_config('app.tenant', '" + tenant + "', true)");
                try (var r = s.executeQuery(sql)) {
                    r.next();
                    return r.getInt(1);
                }
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        });
    }
}
