package com.g1do.anvil.extract;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

/**
 * {@link ExtractionModel} over the Anthropic Messages API (#37, record 0022).
 *
 * <p>The base URL, model and credential come from configuration, so the same
 * code reaches Anthropic's API or an Anthropic-compatible gateway. The base
 * URL must be HTTPS and is operator configuration, never tenant input. The
 * credential comes from the environment and is never logged or stored.
 *
 * <p>Each call is one stateless request: a system prompt, one user message
 * holding the bounded document, and a JSON schema for the reply. No
 * conversation, cache key or tenant identifier is sent.
 */
public final class AnthropicExtractionModel implements ExtractionModel {

    /** How the credential is sent: {@code x-api-key} (Anthropic) or {@code bearer}. */
    public enum Auth {
        X_API_KEY, BEARER
    }

    public record Settings(String baseUrl, String model, String apiKey, Auth auth, String sessionHeader,
            Duration timeout, long maxTokens) {
    }

    static final JsonOutputFormat FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .putAdditionalProperty("required", JsonValue.from(List.of("facts")))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of("facts", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "additionalProperties", false,
                                    "required", List.of("field", "value", "citation"),
                                    "properties", Map.of(
                                            "field", Map.of("type", "string", "enum",
                                                    List.of("party", "person", "date", "amount")),
                                            "value", Map.of("type", "string"),
                                            "citation", Map.of("type", "string")))))))
                    .build())
            .build();

    private final Settings settings;
    private final AnthropicClient client;

    public AnthropicExtractionModel(Settings settings) {
        URI base = URI.create(settings.baseUrl());
        if (!"https".equals(base.getScheme()) || base.getHost() == null) {
            throw new IllegalArgumentException("extraction base URL must be https: " + settings.baseUrl());
        }
        this.settings = settings;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                .baseUrl(settings.baseUrl())
                .timeout(settings.timeout())
                .maxRetries(2)
                .putHeader("User-Agent", "anvil-extract/1.0");
        String key = settings.apiKey() == null ? "" : settings.apiKey();
        // The SDK needs a credential to build a client; an empty one is never sent (complete() refuses first).
        this.client = settings.auth() == Auth.BEARER ? b.authToken(key.isEmpty() ? "unset" : key).build()
                : b.apiKey(key.isEmpty() ? "unset" : key).build();
    }

    @Override
    public String id() {
        return settings.model();
    }

    boolean configured() {
        return settings.apiKey() != null && !settings.apiKey().isBlank();
    }

    @Override
    public ModelReply complete(BoundedText document) {
        if (!configured()) {
            throw new ModelUnavailableException("no model credential is configured");
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(settings.maxTokens())
                .system(Prompt.SYSTEM)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(FORMAT)
                        .build())
                .addUserMessage(Prompt.user(document));
        if (settings.sessionHeader() != null && !settings.sessionHeader().isBlank()) {
            params.putAdditionalHeader(settings.sessionHeader(), UUID.randomUUID().toString());
        }
        Message m;
        try {
            m = client.messages().create(params.build());
        } catch (AnthropicServiceException e) {
            // 408, 409, 429 and 5xx may pass on retry; other codes are a configuration fault. Neither is the document's.
            throw new ModelUnavailableException("model call failed with HTTP " + e.statusCode(), e);
        } catch (AnthropicIoException e) {
            throw new ModelUnavailableException("model call failed: " + e.getClass().getSimpleName(), e);
        }
        StringBuilder text = new StringBuilder();
        m.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        return new ModelReply(text.toString(), m.stopReason().map(r -> r.asString()).orElse(""));
    }
}
