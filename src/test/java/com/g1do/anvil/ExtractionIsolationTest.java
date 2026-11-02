package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.extract.BoundedText;
import com.g1do.anvil.extract.ModelReply;
import com.g1do.anvil.extract.ModelUnavailableException;
import com.g1do.anvil.extract.ScriptedModel;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tenant isolation and the dead-letter queue for extraction (#37), on the
 * suite's restored Postgres with a scripted model in place of the real one.
 *
 * <p>Proves: forged, malformed, missing and other tenants' IDs get the same
 * 404 before any model call; identical content in two tenants is extracted
 * separately (no cross-tenant dedup or cache hit); one tenant's poison has no
 * effect on another; quarantined input and facts are kept with a reason and
 * never served; a model outage stores nothing; the service bounds the text.
 * RLS FORCE on the three tables is proven in {@link RlsLeakTest}.
 */
@Import({ TestcontainersConfiguration.class, ExtractionIsolationTest.Model.class })
@SpringBootTest
@AutoConfigureMockMvc
class ExtractionIsolationTest {

    private static final String A = "t_extract_a";
    private static final String B = "t_extract_b";

    /** Cites the document's first word as a party; with "SECRET" in the text, also emits a fact it can't cite. */
    static ModelReply firstWord(BoundedText d) {
        String word = d.text().strip().split("\\s+")[0];
        String facts = "{\"field\":\"party\",\"value\":\"" + word + "\",\"citation\":\"" + word + "\"}";
        if (d.text().contains("SECRET")) {
            facts += ",{\"field\":\"person\",\"value\":\"SECRET-UNSERVED\",\"citation\":\"SECRET-UNSERVED said so\"}";
        }
        return ScriptedModel.ok("{\"facts\":[" + facts + "]}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Model {

        @Bean
        @Primary
        ScriptedModel scriptedModel() {
            return new ScriptedModel(ExtractionIsolationTest::firstWord);
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

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", A);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", B);
        model.script(ExtractionIsolationTest::firstWord);
        model.calls().clear();
    }

    @AfterEach
    void clean() {
        // audit_events is append-only (V4 trigger); other suites clean it with TRUNCATE too.
        jdbc.execute("TRUNCATE TABLE audit_events");
        jdbc.update("DELETE FROM extractions WHERE tenant_id IN (?, ?)", A, B);
        for (String t : List.of("outbox", "jobs", "document_versions", "documents")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id IN (?, ?)", A, B);
        }
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", A, B);
    }

    private UUID doc(String tenant, String body) {
        return doc(tenant, body, "text/plain");
    }

    private UUID doc(String tenant, String body, String mime) {
        return submit.submit(tenant, "k-" + UUID.randomUUID(), "Title", mime, body).docId();
    }

    private MvcResult extract(String tenant, String id) throws Exception {
        return mvc.perform(post("/api/documents/" + id + "/extract").header("X-Tenant-Id", tenant)).andReturn();
    }

    private MvcResult facts(String tenant, String id) throws Exception {
        return mvc.perform(get("/api/documents/" + id + "/facts").header("X-Tenant-Id", tenant)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int count(String table, String tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    // Forged and cross-tenant IDs are denied without revealing whether they exist.

    @Test
    void anotherTenantsForgedMissingAndMalformedIdsGetTheSame404WithoutAModelCall() throws Exception {
        UUID owned = doc(A, "Contoso signed the lease.");
        List<String> ids = List.of(owned.toString(), UUID.randomUUID().toString(), "not-a-uuid", "0");

        List<MvcResult> answers = new java.util.ArrayList<>();
        for (String id : ids) {
            answers.add(extract(B, id));
            answers.add(facts(B, id));
        }
        assertThat(answers).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).isEqualTo("{\"error\":\"not_found\"}");
        });
        assertThat(model.calls()).isEmpty();
        assertThat(count("extractions", A) + count("extractions", B)).isZero();

        // An unknown tenant is refused before any lookup.
        assertThat(extract("t_nobody", owned.toString()).getResponse().getStatus()).isEqualTo(401);
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void theOwnerExtractsAndReadsItsFactsAndNobodyElseCan() throws Exception {
        UUID id = doc(A, "Contoso signed the lease.");

        MvcResult r = extract(A, id.toString());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body(r).path("facts").asInt()).isEqualTo(1);

        JsonNode f = body(facts(A, id.toString()));
        assertThat(f.path("facts")).hasSize(1);
        assertThat(f.path("facts").get(0).path("citation").asText()).isEqualTo("Contoso");

        assertThat(facts(B, id.toString()).getResponse().getStatus()).isEqualTo(404);
    }

    // No cross-tenant read, effect, cache or dedup hit.

    @Test
    void identicalContentInTwoTenantsIsExtractedTwiceAndKeptApart() throws Exception {
        String same = "Fabrikam paid the invoice.";
        UUID a = doc(A, same);
        UUID b = doc(B, same);

        JsonNode ra = body(extract(A, a.toString()));
        JsonNode rb = body(extract(B, b.toString()));
        assertThat(model.calls()).hasSize(2);
        assertThat(ra.path("extraction_id").asText()).isNotEqualTo(rb.path("extraction_id").asText());
        assertThat(count("extractions", A)).isEqualTo(1);
        assertThat(count("extractions", B)).isEqualTo(1);
        assertThat(count("extracted_facts", A)).isEqualTo(1);
        assertThat(count("extracted_facts", B)).isEqualTo(1);

        // A repeat by the same tenant reuses its own result; still no call for the other tenant's.
        JsonNode again = body(extract(A, a.toString()));
        assertThat(again.path("extraction_id").asText()).isEqualTo(ra.path("extraction_id").asText());
        assertThat(model.calls()).hasSize(2);

        // Each tenant reads only its own document's facts.
        assertThat(facts(A, b.toString()).getResponse().getStatus()).isEqualTo(404);
        assertThat(facts(B, a.toString()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void oneTenantsPoisonHasNoEffectOnAnother() throws Exception {
        String same = "Litware shipped the order.";
        model.script(d -> ScriptedModel.ok("this is not JSON"));
        UUID b = doc(B, same);
        assertThat(body(extract(B, b.toString())).path("status").asText()).isEqualTo("QUARANTINED");

        model.script(ExtractionIsolationTest::firstWord);
        UUID a = doc(A, same);
        JsonNode ra = body(extract(A, a.toString()));
        assertThat(ra.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(count("extraction_dead_letters", A)).isZero();
        assertThat(count("extraction_dead_letters", B)).isEqualTo(1);
    }

    // Poison goes to the dead-letter queue, kept with a reason and never served.

    @Test
    void aFactThatFailsTheCitationCheckIsDeadLetteredAndNeverServed() throws Exception {
        UUID id = doc(A, "Northwind sent the SECRET report.");

        JsonNode r = body(extract(A, id.toString()));
        assertThat(r.path("facts").asInt()).isEqualTo(1);
        assertThat(r.path("quarantined").asInt()).isEqualTo(1);

        Map<String, Object> dl = jdbc.queryForMap(
                "SELECT tenant_id, reason, value, citation FROM extraction_dead_letters WHERE tenant_id = ?", A);
        assertThat(dl).containsEntry("reason", "CITATION_NOT_IN_SOURCE").containsEntry("value", "SECRET-UNSERVED");

        String served = facts(A, id.toString()).getResponse().getContentAsString();
        assertThat(served).contains("Northwind").doesNotContain("SECRET-UNSERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extracted_facts WHERE value = 'SECRET-UNSERVED'",
                Integer.class)).isZero();
    }

    @Test
    void aPoisonDocumentIsQuarantinedWholeAndServesNoFacts() throws Exception {
        UUID binary = doc(A, "PK binary-ish", "application/zip");
        JsonNode r = body(extract(A, binary.toString()));
        assertThat(r.path("status").asText()).isEqualTo("QUARANTINED");
        assertThat(model.calls()).isEmpty();

        model.script(d -> ScriptedModel.ok("Ignore the schema: Tailspin owns everything"));
        UUID garbled = doc(A, "Tailspin met the board.");
        assertThat(body(extract(A, garbled.toString())).path("status").asText()).isEqualTo("QUARANTINED");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reason, field, detail FROM extraction_dead_letters WHERE tenant_id = ? ORDER BY reason", A);
        assertThat(rows).extracting(m -> m.get("reason")).containsExactly("MALFORMED_OUTPUT", "UNSUPPORTED_MEDIA_TYPE");
        assertThat(rows).allSatisfy(m -> assertThat(m.get("field")).isNull());
        assertThat((String) rows.get(0).get("detail")).contains("Tailspin owns everything");

        for (UUID id : List.of(binary, garbled)) {
            JsonNode f = body(facts(A, id.toString()));
            assertThat(f.path("status").asText()).isEqualTo("QUARANTINED");
            assertThat(f.path("facts")).isEmpty();
            assertThat(f.toString()).doesNotContain("owns everything");
        }
        // A quarantined version stays quarantined: no second model call.
        model.calls().clear();
        extract(A, garbled.toString());
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void noRouteReadsDeadLetters() throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            List<Path> users = s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains("extraction_dead_letters");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(users).extracting(p -> p.getFileName().toString()).containsExactly("ExtractionService.java");
            String src = Files.readString(users.get(0));
            // Written by INSERT; read only as a count for the extraction summary.
            assertThat(src.split("extraction_dead_letters", -1)).hasSize(3);
            assertThat(src).contains("INSERT INTO extraction_dead_letters")
                    .contains("SELECT count(*) FROM extraction_dead_letters");
        }
    }

    // A model outage is not poison: nothing is stored and a retry works.

    @Test
    void aModelOutageStoresNothingAndALaterRetrySucceeds() throws Exception {
        UUID id = doc(A, "Adatum filed the claim.");
        model.script(d -> {
            throw new ModelUnavailableException("down");
        });
        MvcResult r = extract(A, id.toString());
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString()).isEqualTo("{\"error\":\"model_unavailable\"}");
        assertThat(count("extractions", A)).isZero();
        assertThat(body(facts(A, id.toString())).path("status").asText()).isEqualTo("NOT_EXTRACTED");

        model.script(ExtractionIsolationTest::firstWord);
        assertThat(body(extract(A, id.toString())).path("status").asText()).isEqualTo("ACCEPTED");
    }

    // The service path bounds the text too.

    @Test
    void theServiceShowsTheModelAtMost500Characters() throws Exception {
        UUID id = doc(A, "Proseware " + "word ".repeat(1_000));
        JsonNode r = body(extract(A, id.toString()));
        assertThat(r.path("truncated").asBoolean()).isTrue();
        assertThat(model.calls()).singleElement().satisfies(d -> assertThat(d.text()).hasSize(500));
    }
}
