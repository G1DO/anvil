package com.g1do.anvil.extract;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.extract.Extractor.Reason;
import com.g1do.anvil.extract.Extractor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Gemini adapter (#37, record 0022), against a scripted transport, so no
 * network or key is needed.
 */
class GeminiExtractionModelTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "https://generativelanguage.googleapis.com";
    static final String KEY = "test-key-not-real";

    record Sent(URI uri, Map<String, String> headers, String body) {
    }

    static final class Scripted implements GeminiExtractionModel.Transport {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<Sent> sent = new ArrayList<>();

        Scripted then(int status, String body) {
            replies.add(new GeminiExtractionModel.Response(status, Optional.empty(), body));
            return this;
        }

        Scripted then(GeminiExtractionModel.Response r) {
            replies.add(r);
            return this;
        }

        Scripted thenThrow(java.io.IOException e) {
            replies.add(e);
            return this;
        }

        @Override
        public GeminiExtractionModel.Response post(URI uri, Map<String, String> headers, String body)
                throws java.io.IOException {
            sent.add(new Sent(uri, headers, body));
            Object r = replies.remove();
            if (r instanceof java.io.IOException e) {
                throw e;
            }
            return (GeminiExtractionModel.Response) r;
        }
    }

    final List<Duration> waits = new ArrayList<>();

    GeminiExtractionModel model(Scripted t, String key, int retries) {
        return new GeminiExtractionModel(
                new GeminiExtractionModel.Settings(BASE, "gemini-3.8-flash", key, Duration.ofSeconds(5), 8192, retries),
                t, waits::add);
    }

    static String candidate(String text, String finish) throws Exception {
        return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("role", "model", "parts", List.of(Map.of("text", text))),
                "finishReason", finish))));
    }

    @Test
    void theRequestCarriesTheKeyInAHeaderTheBoundedDocumentAndTheSchema() throws Exception {
        Scripted t = new Scripted().then(200, candidate("{\"facts\":[]}", "STOP"));
        String doc = "Acme Ltd paid on 3 March 2024. " + "x".repeat(4000) + "TAIL";
        new Extractor(model(t, KEY, 0)).extract(doc, "text/plain");

        Sent s = t.sent.getFirst();
        assertThat(s.uri()).hasToString(BASE + "/v1beta/models/gemini-3.8-flash:generateContent");
        assertThat(s.uri().toString()).doesNotContain(KEY);
        assertThat(s.headers()).containsEntry("x-goog-api-key", KEY);
        JsonNode body = JSON.readTree(s.body());
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo(Prompt.SYSTEM);
        String user = body.at("/contents/0/parts/0/text").asText();
        assertThat(user).contains(BoundedText.of(doc).text()).doesNotContain("TAIL");
        assertThat(body.at("/contents").size()).isEqualTo(1);
        assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/responseSchema/properties/facts/items/properties/field/enum"))
                .extracting(JsonNode::asText).containsExactly("party", "person", "date", "amount");
        assertThat(body.at("/generationConfig/maxOutputTokens").asLong()).isEqualTo(8192);
        assertThat(s.body()).doesNotContain(KEY);
    }

    @Test
    void aReplyWithAnExactCitationIsAcceptedAndThoughtPartsAreIgnored() throws Exception {
        String reply = JSON.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(
                        Map.of("text", "planning, not output", "thought", true),
                        Map.of("text", "{\"facts\":[{\"field\":\"party\",\"value\":\"Acme Ltd\","),
                        Map.of("text", "\"citation\":\"Acme Ltd paid\"}]}"))),
                "finishReason", "STOP"))));
        Extractor.Outcome o = new Extractor(model(new Scripted().then(200, reply), KEY, 0))
                .extract("Acme Ltd paid on 3 March 2024.", "text/plain");
        assertThat(o.status()).isEqualTo(Status.ACCEPTED);
        assertThat(o.accepted()).singleElement().satisfies(f -> assertThat(f.value()).isEqualTo("Acme Ltd"));
    }

    @Test
    void finishReasonsMapOntoTheExtractorsStopReasons() throws Exception {
        assertThat(GeminiExtractionModel.reply(candidate("{}", "STOP")).stopReason()).isEqualTo("end_turn");
        assertThat(GeminiExtractionModel.reply(candidate("{\"fa", "MAX_TOKENS")).stopReason()).isEqualTo("max_tokens");
        for (String r : List.of("SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")) {
            assertThat(GeminiExtractionModel.reply(candidate("", r)).stopReason()).isEqualTo("refusal");
        }
        assertThat(GeminiExtractionModel.reply(candidate("", "OTHER")).stopReason()).isEqualTo("other");
        assertThat(GeminiExtractionModel.reply("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").stopReason())
                .isEqualTo("refusal");
        assertThat(GeminiExtractionModel.reply("{\"candidates\":[]}").stopReason()).isEqualTo("no_candidate");
    }

    @Test
    void aBlockedOrCutOffReplyQuarantinesTheDocument() throws Exception {
        Extractor.Outcome blocked = new Extractor(model(
                new Scripted().then(200, "{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}"), KEY, 0))
                .extract("Acme Ltd paid.", "text/plain");
        assertThat(blocked.status()).isEqualTo(Status.QUARANTINED);
        assertThat(blocked.quarantined()).extracting(q -> q.reason()).containsExactly(Reason.MODEL_REFUSED);

        Extractor.Outcome cut = new Extractor(model(
                new Scripted().then(200, candidate("{\"facts\":[{\"field\":\"party\"", "MAX_TOKENS")), KEY, 0))
                .extract("Acme Ltd paid.", "text/plain");
        assertThat(cut.quarantined()).extracting(q -> q.reason()).containsExactly(Reason.MALFORMED_OUTPUT);
    }

    @Test
    void rateLimitsAndServerErrorsAreRetriedWithBackoff() throws Exception {
        Scripted t = new Scripted()
                .then(new GeminiExtractionModel.Response(429, Optional.of("7"), "{}"))
                .then(503, "{}")
                .thenThrow(new java.net.http.HttpTimeoutException("slow"))
                .then(200, candidate("{\"facts\":[]}", "STOP"));
        ModelReply r = model(t, KEY, 3).complete(BoundedText.of("Doc."));
        assertThat(r.stopReason()).isEqualTo("end_turn");
        assertThat(t.sent).hasSize(4);
        assertThat(waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(GeminiExtractionModel.backoff(9, Optional.empty())).isEqualTo(Duration.ofSeconds(60));
        assertThat(GeminiExtractionModel.backoff(0, Optional.of("600"))).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void anOutageIsUnavailableNotPoisonAndTheKeyIsNeverInTheError() {
        Scripted down = new Scripted().then(503, "{}").then(503, "{}").then(503, "{}");
        assertThatThrownBy(() -> model(down, KEY, 2).complete(BoundedText.of("Doc.")))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("HTTP 503")
                .hasMessageNotContaining(KEY);
        assertThat(down.sent).hasSize(3);

        Scripted badKey = new Scripted().then(400, "{\"error\":{\"message\":\"API key not valid\"}}");
        assertThatThrownBy(() -> model(badKey, KEY, 5).complete(BoundedText.of("Doc.")))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("HTTP 400");
        assertThat(badKey.sent).as("a configuration fault is not retried").hasSize(1);
    }

    @Test
    void itNeedsHttpsAPlainModelIdAndAKeyBeforeAnyCall() {
        Scripted t = new Scripted();
        assertThatThrownBy(() -> new GeminiExtractionModel(new GeminiExtractionModel.Settings(
                "http://generativelanguage.googleapis.com", "gemini-3.8-flash", KEY, Duration.ofSeconds(5), 8192, 0),
                t, waits::add)).isInstanceOf(IllegalArgumentException.class);
        for (String bad : List.of("../files", "models/x", "gemini?key=1", "", "Gemini")) {
            assertThatThrownBy(() -> new GeminiExtractionModel(new GeminiExtractionModel.Settings(
                    BASE, bad, KEY, Duration.ofSeconds(5), 8192, 0), t, waits::add))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> model(t, " ", 0).complete(BoundedText.of("Doc.")))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("no model credential");
        assertThat(t.sent).isEmpty();
    }

    @Test
    void theHarnessPicksTheProviderFromTheEnvironment() {
        assertThat(ExtractHarness.model(Map.of())).isInstanceOf(GeminiExtractionModel.class)
                .satisfies(m -> assertThat(m.id()).isEqualTo("gemini-3.8-flash"));
        assertThat(ExtractHarness.model(Map.of("ANVIL_MODEL_PROVIDER", "anthropic")))
                .isInstanceOf(AnthropicExtractionModel.class)
                .satisfies(m -> assertThat(m.id()).isEqualTo("claude-haiku-5-5"));
        assertThatThrownBy(() -> ExtractHarness.model(Map.of("ANVIL_MODEL_PROVIDER", "other")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
