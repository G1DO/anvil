package com.g1do.anvil.extract;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.extract.Extractor.Reason;
import com.g1do.anvil.extract.Extractor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DeepSeek adapter (#37, record 0022), against a scripted transport, so
 * no network or key is needed.
 */
class DeepSeekExtractionModelTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "https://api.deepseek.com";
    static final String KEY = "test-key-not-real";

    record Sent(URI uri, Map<String, String> headers, String body) {
    }

    static final class Scripted implements DeepSeekExtractionModel.Transport {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<Sent> sent = new ArrayList<>();

        Scripted then(int status, String body) {
            replies.add(new DeepSeekExtractionModel.Response(status, body));
            return this;
        }

        Scripted thenThrow(IOException e) {
            replies.add(e);
            return this;
        }

        @Override
        public DeepSeekExtractionModel.Response post(URI uri, Map<String, String> headers, String body)
                throws IOException {
            sent.add(new Sent(uri, headers, body));
            Object r = replies.remove();
            if (r instanceof IOException e) {
                throw e;
            }
            return (DeepSeekExtractionModel.Response) r;
        }
    }

    final List<Duration> waits = new ArrayList<>();

    DeepSeekExtractionModel model(Scripted t, String key, int retries) {
        return new DeepSeekExtractionModel(
                new DeepSeekExtractionModel.Settings(BASE, "deepseek-flash", key, Duration.ofSeconds(5), 4096, retries),
                t, waits::add);
    }

    static String choice(String content, String finish) throws Exception {
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", content),
                "finish_reason", finish))));
    }

    @Test
    void theRequestSendsTheBoundedDocumentInJsonModeWithThinkingOff() throws Exception {
        Scripted t = new Scripted().then(200, choice("{\"facts\":[]}", "stop"));
        String doc = "Acme Ltd paid on 3 March 2024. " + "x".repeat(4000) + "TAIL";
        new Extractor(model(t, KEY, 0)).extract(doc, "text/plain");

        Sent s = t.sent.getFirst();
        assertThat(s.uri()).hasToString(BASE + "/chat/completions");
        assertThat(s.headers()).containsEntry("Authorization", "Bearer " + KEY);
        assertThat(s.body()).doesNotContain(KEY);
        JsonNode body = JSON.readTree(s.body());
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo(Prompt.SYSTEM);
        assertThat(body.path("messages").size()).isEqualTo(2);
        String user = body.at("/messages/1/content").asText();
        assertThat(user).contains(BoundedText.of(doc).text()).doesNotContain("TAIL");
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("max_tokens").asLong()).isEqualTo(4096);
        assertThat(body.path("stream").asBoolean()).isFalse();
        // JSON mode needs the word and an example of the shape in the prompt.
        assertThat(Prompt.SYSTEM).contains("json").contains("{\"facts\": [{\"field\"");
    }

    @Test
    void aReplyWithAnExactCitationIsAcceptedEvenAfterKeepAliveLines() throws Exception {
        String reply = "\n\n\n" + choice(
                "{\"facts\":[{\"field\":\"party\",\"value\":\"Acme Ltd\",\"citation\":\"Acme Ltd paid\"}]}", "stop");
        Extractor.Outcome o = new Extractor(model(new Scripted().then(200, reply), KEY, 0))
                .extract("Acme Ltd paid on 3 March 2024.", "text/plain");
        assertThat(o.status()).isEqualTo(Status.ACCEPTED);
        assertThat(o.accepted()).singleElement().satisfies(f -> assertThat(f.value()).isEqualTo("Acme Ltd"));
    }

    @Test
    void finishReasonsMapOntoTheExtractorsStopReasons() throws Exception {
        assertThat(DeepSeekExtractionModel.reply(choice("{}", "stop")).stopReason()).isEqualTo("end_turn");
        assertThat(DeepSeekExtractionModel.reply(choice("{\"fa", "length")).stopReason()).isEqualTo("max_tokens");
        assertThat(DeepSeekExtractionModel.reply(choice("", "content_filter")).stopReason()).isEqualTo("refusal");
        assertThat(DeepSeekExtractionModel.reply(choice("", "insufficient_system_resource")).stopReason())
                .isEqualTo("insufficient_system_resource");
        assertThat(DeepSeekExtractionModel.reply("{\"choices\":[]}").stopReason()).isEqualTo("no_choice");
    }

    @Test
    void aFilteredCutOffOrBlankReplyQuarantinesTheDocument() throws Exception {
        Extractor.Outcome filtered = new Extractor(model(new Scripted().then(200, choice("", "content_filter")),
                KEY, 0)).extract("Acme Ltd paid.", "text/plain");
        assertThat(filtered.status()).isEqualTo(Status.QUARANTINED);
        assertThat(filtered.quarantined()).extracting(q -> q.reason()).containsExactly(Reason.MODEL_REFUSED);

        Extractor.Outcome cut = new Extractor(model(
                new Scripted().then(200, choice("{\"facts\":[{\"field\":\"party\"", "length")), KEY, 0))
                .extract("Acme Ltd paid.", "text/plain");
        assertThat(cut.quarantined()).extracting(q -> q.reason()).containsExactly(Reason.MALFORMED_OUTPUT);

        // JSON mode blanks out non-JSON output, so a reply can be spaces with finish reason stop.
        Extractor.Outcome blank = new Extractor(model(new Scripted().then(200, choice("   ", "stop")), KEY, 0))
                .extract("Acme Ltd paid.", "text/plain");
        assertThat(blank.quarantined()).extracting(q -> q.reason()).containsExactly(Reason.MALFORMED_OUTPUT);
    }

    @Test
    void rateLimitsOverloadAndTimeoutsAreRetriedWithBackoff() throws Exception {
        Scripted t = new Scripted()
                .then(429, "{\"error\":{\"message\":\"Rate Limit Reached\"}}")
                .then(503, "{}")
                .thenThrow(new HttpTimeoutException("slow"))
                .then(200, choice("{\"facts\":[]}", "stop"));
        assertThat(model(t, KEY, 3).complete(BoundedText.of("Doc.")).stopReason()).isEqualTo("end_turn");
        assertThat(t.sent).hasSize(4);
        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(DeepSeekExtractionModel.backoff(9)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void anOutageIsUnavailableNotPoisonAndAConfigurationFaultIsNotRetried() {
        Scripted down = new Scripted().then(503, "{}").then(503, "{}").then(503, "{}");
        assertThatThrownBy(() -> model(down, KEY, 2).complete(BoundedText.of("Doc.")))
                .isInstanceOf(ModelUnavailableException.class).hasMessage("model call failed with HTTP 503");
        assertThat(down.sent).hasSize(3);

        for (int status : List.of(400, 401, 402, 422)) {
            Scripted fault = new Scripted().then(status, "{\"error\":{\"message\":\"no\"}}");
            assertThatThrownBy(() -> model(fault, KEY, 5).complete(BoundedText.of("Doc.")))
                    .isInstanceOf(ModelUnavailableException.class)
                    .hasMessage("model call failed with HTTP " + status);
            assertThat(fault.sent).hasSize(1);
        }
    }

    @Test
    void itNeedsHttpsAPlainModelIdAndAKeyBeforeAnyCall() {
        Scripted t = new Scripted();
        assertThatThrownBy(() -> new DeepSeekExtractionModel(new DeepSeekExtractionModel.Settings(
                "http://api.deepseek.com", "deepseek-flash", KEY, Duration.ofSeconds(5), 4096, 0), t, waits::add))
                .isInstanceOf(IllegalArgumentException.class);
        for (String bad : List.of("../x", "a/b", "m?x=1", "", "DeepSeek")) {
            assertThatThrownBy(() -> new DeepSeekExtractionModel(new DeepSeekExtractionModel.Settings(
                    BASE, bad, KEY, Duration.ofSeconds(5), 4096, 0), t, waits::add))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> model(t, " ", 0).complete(BoundedText.of("Doc.")))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("no model credential");
        assertThat(t.sent).isEmpty();
    }

    @Test
    void theHarnessReadsTheModelFromTheEnvironment() {
        assertThat(ExtractHarness.model(Map.of()).id()).isEqualTo("deepseek-flash");
        assertThat(ExtractHarness.model(Map.of("ANVIL_MODEL", "deepseek-v4-pro")).id()).isEqualTo("deepseek-v4-pro");
        assertThatThrownBy(() -> ExtractHarness.model(Map.of("ANVIL_MODEL_BASE_URL", "http://api.deepseek.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
