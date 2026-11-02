package com.g1do.anvil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.eval.CitationCheck;
import com.g1do.anvil.eval.EvalSet;
import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.Prediction;
import com.g1do.anvil.extract.BoundedText;
import com.g1do.anvil.extract.ExtractionService;
import com.g1do.anvil.extract.ExtractionService.FallbackReason;
import com.g1do.anvil.extract.ExtractionService.Result;
import com.g1do.anvil.extract.ExtractionService.Source;
import com.g1do.anvil.extract.Extractor;
import com.g1do.anvil.extract.ModelReply;
import com.g1do.anvil.extract.ModelUnavailableException;
import com.g1do.anvil.extract.Prompt;
import com.g1do.anvil.extract.ScriptedModel;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The cache, budget, kill-switch and rules fallback of extraction (#38,
 * record 0023), on the suite's restored Postgres with a scripted model.
 *
 * <p>Proves: a cache hit in the same tenant makes no model call; there is no
 * cross-tenant hit; a quarantined, failing or revoked entry is never served
 * or reused; each call takes one from the tenant's budget; an exhausted budget
 * or the kill-switch gives the rules fallback with zero model calls, marked as
 * fallback; the budget holds across processes; and, over all 52 documents of
 * the frozen set, the counts the cost report publishes.
 */
@Import({ TestcontainersConfiguration.class, ExtractionCostTest.Model.class })
@SpringBootTest
@AutoConfigureMockMvc
class ExtractionCostTest {

    private static final String A = "t_cost_a";
    private static final String B = "t_cost_b";
    private static final Path SET = Path.of("eval/sets/pilot-v1");

    /** Cites the document's first word as a party. */
    static ModelReply firstWord(BoundedText d) {
        String word = d.text().strip().split("\\s+")[0];
        return ScriptedModel.ok("{\"facts\":[{\"field\":\"party\",\"value\":\"" + word + "\",\"citation\":\"" + word
                + "\"}]}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Model {

        @Bean
        @Primary
        ScriptedModel scriptedModel() {
            return new ScriptedModel(ExtractionCostTest::firstWord);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submit;

    @Autowired
    ScriptedModel model;

    @Autowired
    MockMvc mvc;

    @Autowired
    ExtractionService service;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    Extractor extractor;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO tenants (id) VALUES (?), (?) ON CONFLICT DO NOTHING", A, B);
        model.script(ExtractionCostTest::firstWord);
        model.calls().clear();
    }

    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE audit_events");
        jdbc.update("UPDATE extraction_switch SET model_calls_enabled = TRUE, reason = 'test reset'");
        jdbc.update("INSERT INTO extraction_switch (model_calls_enabled, reason) VALUES (TRUE, 'test reset') "
                + "ON CONFLICT (id) DO NOTHING");
        jdbc.update("DELETE FROM extraction_budgets WHERE tenant_id IN (?, ?)", A, B);
        jdbc.update("DELETE FROM extractions WHERE tenant_id IN (?, ?)", A, B);
        for (String t : List.of("outbox", "jobs", "document_versions", "documents")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id IN (?, ?)", A, B);
        }
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", A, B);
    }

    private void budget(String tenant, long calls) {
        jdbc.update("INSERT INTO extraction_budgets (tenant_id, calls_remaining) VALUES (?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET calls_remaining = EXCLUDED.calls_remaining", tenant, calls);
    }

    private long remaining(String tenant) {
        return jdbc.queryForObject("SELECT calls_remaining FROM extraction_budgets WHERE tenant_id = ?", Long.class,
                tenant);
    }

    private long used(String tenant) {
        return jdbc.queryForObject("SELECT calls_used FROM extraction_budgets WHERE tenant_id = ?", Long.class,
                tenant);
    }

    private void killSwitch(boolean modelCallsEnabled) {
        jdbc.update("UPDATE extraction_switch SET model_calls_enabled = ?, reason = 'test', changed_at = now()",
                modelCallsEnabled);
    }

    private UUID doc(String tenant, String body) {
        return submit.submit(tenant, "k-" + UUID.randomUUID(), "Title", "text/plain", body).docId();
    }

    private JsonNode extract(String tenant, UUID id) throws Exception {
        MvcResult r = mvc.perform(post("/api/documents/" + id + "/extract").header("X-Tenant-Id", tenant))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode facts(String tenant, UUID id) throws Exception {
        return json.readTree(mvc.perform(get("/api/documents/" + id + "/facts").header("X-Tenant-Id", tenant))
                .andReturn().getResponse().getContentAsString());
    }

    /** The statement record 0023 gives operators: revoke a result and every copy made from it. */
    private void revoke(String tenant, String extractionId) {
        jdbc.update("INSERT INTO extraction_revocations (tenant_id, extraction_id, reason) "
                + "SELECT tenant_id, id, 'test revoke' FROM extractions "
                + "WHERE tenant_id = ? AND (id = ?::uuid OR cache_of = ?::uuid) ON CONFLICT DO NOTHING",
                tenant, extractionId, extractionId);
    }

    private static final String TEXT = "Northwind paid Contoso Ltd on 3 March 2026.";

    // Acceptance: a per-tenant cache keyed by tenant, content, prompt and model; hits make no model call.

    @Test
    void aHitInTheSameTenantMakesNoModelCallAndSpendsNoBudget() throws Exception {
        budget(A, 10);
        JsonNode first = extract(A, doc(A, TEXT));
        assertThat(first.path("source").asText()).isEqualTo("MODEL");
        assertThat(first.path("fallback").asBoolean()).isFalse();
        assertThat(first.path("fallback_reason").isNull()).isTrue();
        assertThat(model.calls()).hasSize(1);
        assertThat(remaining(A)).isEqualTo(9);

        // Another document with the same text, and one whose padding the extractor drops: both hits.
        for (String body : List.of(TEXT, "  \n\t" + TEXT)) {
            UUID other = doc(A, body);
            JsonNode hit = extract(A, other);
            assertThat(hit.path("source").asText()).isEqualTo("CACHE");
            assertThat(hit.path("fallback").asBoolean()).isFalse();
            assertThat(hit.path("facts").asInt()).isEqualTo(first.path("facts").asInt());
            assertThat(facts(A, other).path("facts")).isEqualTo(
                    json.readTree("[{\"field\":\"party\",\"value\":\"Northwind\",\"citation\":\"Northwind\"}]"));
            assertThat(facts(A, other).path("source").asText()).isEqualTo("CACHE");
        }
        assertThat(model.calls()).hasSize(1);
        assertThat(remaining(A)).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT DISTINCT cache_of::text FROM extractions WHERE tenant_id = ? "
                + "AND source = 'CACHE'", String.class, A)).containsExactly(first.path("extraction_id").asText());
    }

    @Test
    void theKeyHoldsThePromptAndModelSoAnEntryFromAnotherIsNotAHit() throws Exception {
        budget(A, 10);
        extract(A, doc(A, TEXT));
        jdbc.update("UPDATE extractions SET prompt_sha256 = ? WHERE tenant_id = ?", "0".repeat(64), A);
        assertThat(extract(A, doc(A, TEXT)).path("source").asText()).isEqualTo("MODEL");
        jdbc.update("UPDATE extractions SET model = 'another-model' WHERE tenant_id = ? AND prompt_sha256 = ?", A,
                Prompt.SHA256);
        assertThat(extract(A, doc(A, TEXT)).path("source").asText()).isEqualTo("MODEL");
        assertThat(model.calls()).hasSize(3);
    }

    // Acceptance: no cross-tenant hits.

    @Test
    void identicalContentInAnotherTenantIsNeverAHit() throws Exception {
        budget(A, 10);
        extract(A, doc(A, TEXT));

        // Even when a hit would save tenant B its only way to the model, it gets the rules, not A's result.
        budget(B, 0);
        JsonNode none = extract(B, doc(B, TEXT));
        assertThat(none.path("source").asText()).isEqualTo("RULES");
        assertThat(none.path("fallback_reason").asText()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(model.calls()).hasSize(1);

        // With budget, B makes its own call; B's later hits copy B's own entry, never A's.
        budget(B, 10);
        JsonNode own = extract(B, doc(B, TEXT));
        assertThat(own.path("source").asText()).isEqualTo("MODEL");
        assertThat(model.calls()).hasSize(2);
        assertThat(remaining(B)).isEqualTo(9);
        JsonNode hit = extract(B, doc(B, TEXT));
        assertThat(hit.path("source").asText()).isEqualTo("CACHE");
        assertThat(jdbc.queryForObject("SELECT cache_of::text FROM extractions WHERE id = ?::uuid", String.class,
                hit.path("extraction_id").asText())).isEqualTo(own.path("extraction_id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extractions WHERE tenant_id = ? AND cache_of IN "
                + "(SELECT id FROM extractions WHERE tenant_id = ?)", Integer.class, B, A)).isZero();
    }

    // Acceptance: poisoned entries are never served.

    @Test
    void aQuarantinedEntryIsNeverReused() throws Exception {
        budget(A, 10);
        model.script(d -> ScriptedModel.ok("not json"));
        JsonNode poisoned = extract(A, doc(A, TEXT));
        assertThat(poisoned.path("status").asText()).isEqualTo("QUARANTINED");

        model.script(ExtractionCostTest::firstWord);
        JsonNode fresh = extract(A, doc(A, TEXT));
        assertThat(fresh.path("source").asText()).isEqualTo("MODEL");
        assertThat(fresh.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(model.calls()).hasSize(2);
    }

    @Test
    void anEntryWithAFactThatFailsTheChecksAgainIsNeverReused() throws Exception {
        budget(A, 10);
        JsonNode first = extract(A, doc(A, TEXT));
        // Stand in for a tampered entry, or one written under weaker checks: its citation isn't in the text.
        jdbc.update("UPDATE extracted_facts SET value = 'Evil Corp', citation = 'Evil Corp' WHERE tenant_id = ? "
                + "AND extraction_id = ?::uuid", A, first.path("extraction_id").asText());

        UUID other = doc(A, TEXT);
        JsonNode r = extract(A, other);
        assertThat(r.path("source").asText()).isEqualTo("MODEL");
        assertThat(model.calls()).hasSize(2);
        assertThat(facts(A, other).path("facts").toString()).doesNotContain("Evil Corp");
    }

    // Acceptance: revoked entries are never served.

    @Test
    void aRevokedResultIsNeverServedOrReusedAndItsVersionFallsBackWithNoModelCall() throws Exception {
        budget(A, 10);
        UUID one = doc(A, TEXT);
        JsonNode first = extract(A, one);
        revoke(A, first.path("extraction_id").asText());

        // Not served: the facts route reads it as not extracted.
        JsonNode read = facts(A, one);
        assertThat(read.path("status").asText()).isEqualTo("NOT_EXTRACTED");
        assertThat(read.path("facts")).isEmpty();

        // Not reused: the same text in another document goes to the model.
        assertThat(extract(A, doc(A, TEXT)).path("source").asText()).isEqualTo("MODEL");
        assertThat(model.calls()).hasSize(2);

        // Its own version is answered by the rules, marked, with no model call or budget spent.
        long before = remaining(A);
        JsonNode again = extract(A, one);
        assertThat(again.path("source").asText()).isEqualTo("RULES");
        assertThat(again.path("fallback").asBoolean()).isTrue();
        assertThat(again.path("fallback_reason").asText()).isEqualTo("REVOKED");
        assertThat(model.calls()).hasSize(2);
        assertThat(remaining(A)).isEqualTo(before);
        JsonNode served = facts(A, one);
        assertThat(served.path("fallback").asBoolean()).isTrue();
        assertThat(served.path("source").asText()).isEqualTo("RULES");
        assertThat(served.path("facts").toString()).contains("Contoso Ltd").contains("3 March 2026");
    }

    @Test
    void aCopyOfARevokedResultIsNotServedEither() throws Exception {
        budget(A, 10);
        JsonNode original = extract(A, doc(A, TEXT));
        UUID copy = doc(A, TEXT);
        assertThat(extract(A, copy).path("source").asText()).isEqualTo("CACHE");

        // Revoke only the original's own row: the copy reads as revoked through cache_of.
        jdbc.update("INSERT INTO extraction_revocations (tenant_id, extraction_id, reason) VALUES (?, ?::uuid, 'x')",
                A, original.path("extraction_id").asText());
        assertThat(facts(A, copy).path("status").asText()).isEqualTo("NOT_EXTRACTED");
        assertThat(extract(A, copy).path("fallback_reason").asText()).isEqualTo("REVOKED");
        assertThat(model.calls()).hasSize(1);
    }

    // Acceptance: each call decrements a budget; an exhausted one forces the fallback with zero model calls.

    @Test
    void anExhaustedBudgetGivesTheRulesWithNoModelCallUntilItIsToppedUp() throws Exception {
        budget(A, 1);
        extract(A, doc(A, "Fabrikam signed."));
        assertThat(remaining(A)).isZero();
        assertThat(used(A)).isEqualTo(1);

        UUID id = doc(A, TEXT);
        JsonNode r = extract(A, id);
        assertThat(r.path("source").asText()).isEqualTo("RULES");
        assertThat(r.path("fallback").asBoolean()).isTrue();
        assertThat(r.path("fallback_reason").asText()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(model.calls()).hasSize(1);
        assertThat(used(A)).isEqualTo(1);

        // The fallback's facts pass the same checks: every citation is in the text.
        JsonNode served = facts(A, id);
        assertThat(served.path("fallback").asBoolean()).isTrue();
        assertThat(served.path("facts")).isNotEmpty().allSatisfy(
                f -> assertThat(CitationCheck.isExactSubstring(TEXT, f.path("citation").asText())).isTrue());
        // A repeat returns the stored fallback, still with no call.
        assertThat(extract(A, id).path("extraction_id")).isEqualTo(r.path("extraction_id"));
        assertThat(model.calls()).hasSize(1);

        // Once topped up, the model extracts the version and its result is served instead.
        budget(A, 5);
        JsonNode upgraded = extract(A, id);
        assertThat(upgraded.path("source").asText()).isEqualTo("MODEL");
        assertThat(model.calls()).hasSize(2);
        assertThat(facts(A, id).path("fallback").asBoolean()).isFalse();
        assertThat(facts(A, id).path("source").asText()).isEqualTo("MODEL");
    }

    @Test
    void aTenantWithNoBudgetRowMakesNoModelCall() throws Exception {
        JsonNode r = extract(A, doc(A, TEXT));
        assertThat(r.path("fallback_reason").asText()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void aCallThatFailsStillCountsAndStoresNothing() throws Exception {
        budget(A, 2);
        model.script(d -> {
            throw new ModelUnavailableException("down");
        });
        UUID id = doc(A, TEXT);
        int status = mvc.perform(post("/api/documents/" + id + "/extract").header("X-Tenant-Id", A)).andReturn()
                .getResponse().getStatus();
        assertThat(status).isEqualTo(503);
        assertThat(remaining(A)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extractions WHERE tenant_id = ?", Integer.class, A))
                .isZero();
    }

    @Test
    void poisonIsQuarantinedBeforeTheBudgetOrSwitchIsConsulted() throws Exception {
        budget(A, 0);
        killSwitch(false);
        JsonNode r = extract(A, doc(A, "hidden‮text"));
        assertThat(r.path("status").asText()).isEqualTo("QUARANTINED");
        assertThat(r.path("source").asText()).isEqualTo("MODEL");
        assertThat(r.path("fallback").asBoolean()).isFalse();
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void theBudgetHoldsAcrossProcesses() throws Exception {
        budget(A, 3);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(doc(A, "Org" + i + " signed."));
        }
        // A second service instance stands in for another process: it shares the database, not the claim.
        ExtractionService other = new ExtractionService(jdbc, txManager, extractor);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                ExtractionService s = i % 2 == 0 ? service : other;
                UUID id = ids.get(i);
                results.add(pool.submit(() -> s.extract(A, id).orElseThrow()));
            }
            int rules = 0;
            for (Future<Result> f : results) {
                Result r = f.get(60, TimeUnit.SECONDS);
                rules += r.fallback() ? 1 : 0;
                assertThat(r.fallbackReason()).isIn(null, FallbackReason.BUDGET_EXHAUSTED);
            }
            assertThat(model.calls()).hasSize(3);
            assertThat(rules).isEqualTo(7);
            assertThat(remaining(A)).isZero();
            assertThat(used(A)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    // Acceptance: the kill-switch forces the fallback with zero model calls.

    @Test
    void theKillSwitchGivesTheRulesForEveryTenantWithNoModelCall() throws Exception {
        budget(A, 10);
        budget(B, 10);
        extract(A, doc(A, TEXT));
        killSwitch(false);

        // A new text, and a text that would be a cache hit: both fall back.
        for (String body : List.of("Litware signed on 2 May 2026.", TEXT)) {
            JsonNode r = extract(A, doc(A, body));
            assertThat(r.path("source").asText()).isEqualTo("RULES");
            assertThat(r.path("fallback_reason").asText()).isEqualTo("KILL_SWITCH");
        }
        assertThat(extract(B, doc(B, TEXT)).path("fallback_reason").asText()).isEqualTo("KILL_SWITCH");
        assertThat(model.calls()).hasSize(1);
        assertThat(remaining(A)).isEqualTo(9);
        assertThat(remaining(B)).isEqualTo(10);

        // A stored model result is still served: the switch stops calls, revocation stops results.
        JsonNode stored = facts(A, jdbc.queryForObject("SELECT document_id FROM extractions WHERE tenant_id = ? "
                + "AND source = 'MODEL'", UUID.class, A));
        assertThat(stored.path("source").asText()).isEqualTo("MODEL");
        assertThat(stored.path("facts")).hasSize(1);
    }

    @Test
    void aMissingSwitchRowReadsAsOff() throws Exception {
        budget(A, 10);
        jdbc.update("DELETE FROM extraction_switch");
        assertThat(extract(A, doc(A, TEXT)).path("fallback_reason").asText()).isEqualTo("KILL_SWITCH");
        assertThat(model.calls()).isEmpty();
    }

    // The runtime role can read the controls and spend its budget, nothing more.

    @Test
    void theRuntimeRoleCanOnlyReadTheControlsAndSpendItsBudget() {
        for (String t : List.of("extraction_revocations", "extraction_switch")) {
            assertThat(jdbc.queryForList("SELECT privilege_type FROM information_schema.role_table_grants "
                    + "WHERE grantee = 'anvil_app' AND table_name = ? ORDER BY privilege_type", String.class, t))
                    .as(t).containsExactly("SELECT");
        }
        assertThat(jdbc.queryForList("SELECT privilege_type FROM information_schema.role_table_grants "
                + "WHERE grantee = 'anvil_app' AND table_name = 'extraction_budgets'", String.class))
                .containsExactly("SELECT");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.column_privileges "
                + "WHERE grantee = 'anvil_app' AND table_name = 'extraction_budgets' AND privilege_type = 'UPDATE' "
                + "ORDER BY column_name", String.class))
                .containsExactly("calls_remaining", "calls_used", "updated_at");
    }

    // Acceptance: at harness scale, the counts the cost report publishes.

    /**
     * All 52 documents of the frozen set through the service, the model
     * replaying the committed run's predictions: a cold pass calls the model
     * once per document and spends 52 calls; the same texts again are 52 hits
     * with no call; another tenant with no budget gets 52 rules fallbacks with
     * no call, exactly the frozen baseline; the kill-switch gives 52 more.
     */
    @Test
    void atHarnessScaleTheCacheAndFallbackGiveThePublishedCounts() throws Exception {
        EvalSet set = EvalSet.load(SET);
        Map<String, Prediction> committed = EvalSet.readPredictions(
                Path.of("eval/runs/extract-v1-deepseek-flash/predictions.jsonl"), new java.util.HashSet<>(set.docIds()));
        Map<String, String> texts = new java.util.LinkedHashMap<>();
        set.quality().forEach(d -> texts.put(d.id(), d.text()));
        set.injection().forEach(c -> texts.put(c.id(), c.text()));
        Map<String, String> replyFor = new HashMap<>();
        for (Map.Entry<String, String> e : texts.entrySet()) {
            replyFor.put(e.getValue(), json.writeValueAsString(Map.of("facts", committed.get(e.getKey()).facts())));
        }
        model.script(d -> ScriptedModel.ok(replyFor.get(d.text())));
        budget(A, texts.size());

        Map<Source, Integer> cold = pass(A, texts, committed, null);
        assertThat(cold).containsEntry(Source.MODEL, 52);
        assertThat(model.calls()).hasSize(52);
        assertThat(remaining(A)).isZero();

        Map<Source, Integer> warm = pass(A, texts, committed, null);
        assertThat(warm).containsEntry(Source.CACHE, 52);
        assertThat(model.calls()).hasSize(52);

        Map<Source, Integer> spent = pass(B, texts, set.baseline(), FallbackReason.BUDGET_EXHAUSTED);
        assertThat(spent).containsEntry(Source.RULES, 52);
        assertThat(model.calls()).hasSize(52);

        budget(A, 1000);
        killSwitch(false);
        Map<Source, Integer> killed = pass(A, texts, set.baseline(), FallbackReason.KILL_SWITCH);
        assertThat(killed).containsEntry(Source.RULES, 52);
        assertThat(model.calls()).hasSize(52);
        assertThat(remaining(A)).isEqualTo(1000);
    }

    /** Submits every text as a new document, extracts it, and checks its facts against {@code expected}. */
    private Map<Source, Integer> pass(String tenant, Map<String, String> texts, Map<String, Prediction> expected,
            FallbackReason reason) {
        Map<Source, Integer> sources = new HashMap<>();
        for (Map.Entry<String, String> e : texts.entrySet()) {
            UUID id = doc(tenant, e.getValue());
            Result r = service.extract(tenant, id).orElseThrow();
            sources.merge(r.source(), 1, Integer::sum);
            assertThat(r.fallbackReason()).as(e.getKey()).isEqualTo(reason);
            List<Fact> served = service.facts(tenant, id).orElseThrow().facts();
            assertThat(served).as(e.getKey()).containsExactlyInAnyOrderElementsOf(expected.get(e.getKey()).facts());
        }
        return sources;
    }
}
