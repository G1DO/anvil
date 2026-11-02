package com.g1do.anvil.extract;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ExtractionModel} over the Gemini API's {@code generateContent}
 * (#37, record 0022).
 *
 * <p>The base URL, model and credential come from configuration. The base URL
 * must be HTTPS and is operator configuration, never tenant input. The key is
 * sent in the {@code x-goog-api-key} header, never in the URL, and is never
 * logged or stored.
 *
 * <p>Each call is one stateless request: a system instruction, one user
 * message holding the bounded document, and a response schema. No
 * conversation, cache key or tenant identifier is sent. The reply's finish
 * reason is mapped onto the stop reasons {@link Extractor} decides on:
 * {@code STOP} is {@code end_turn}, {@code MAX_TOKENS} is {@code max_tokens},
 * a blocked prompt or a safety, recitation, language or blocklist stop is
 * {@code refusal}, and anything else passes through and is quarantined as
 * malformed.
 */
public final class GeminiExtractionModel implements ExtractionModel {

    public record Settings(String baseUrl, String model, String apiKey, Duration timeout, long maxTokens,
            int maxRetries) {
    }

    /** One HTTP exchange; replaced in tests. */
    interface Transport {
        Response post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException;
    }

    record Response(int status, Optional<String> retryAfter, String body) {
    }

    /** Waits between attempts; replaced in tests. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "required", List.of("facts"),
            "properties", Map.of("facts", Map.of(
                    "type", "ARRAY",
                    "items", Map.of(
                            "type", "OBJECT",
                            "required", List.of("field", "value", "citation"),
                            "propertyOrdering", List.of("field", "value", "citation"),
                            "properties", Map.of(
                                    "field", Map.of("type", "STRING", "enum",
                                            List.of("party", "person", "date", "amount")),
                                    "value", Map.of("type", "STRING"),
                                    "citation", Map.of("type", "STRING"))))));

    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9.\\-]{0,63}");
    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST",
            "PROHIBITED_CONTENT", "SPII");
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Settings settings;
    private final URI endpoint;
    private final Transport transport;
    private final Sleeper sleeper;

    public GeminiExtractionModel(Settings settings) {
        this(settings, httpTransport(settings.timeout()), d -> Thread.sleep(d.toMillis()));
    }

    GeminiExtractionModel(Settings settings, Transport transport, Sleeper sleeper) {
        URI base = URI.create(settings.baseUrl());
        if (!"https".equals(base.getScheme()) || base.getHost() == null) {
            throw new IllegalArgumentException("extraction base URL must be https: " + settings.baseUrl());
        }
        if (settings.model() == null || !MODEL_ID.matcher(settings.model()).matches()) {
            throw new IllegalArgumentException("not a Gemini model id: " + settings.model());
        }
        this.settings = settings;
        this.endpoint = URI.create(settings.baseUrl().replaceAll("/+$", "") + "/v1beta/models/" + settings.model()
                + ":generateContent");
        this.transport = transport;
        this.sleeper = sleeper;
    }

    @Override
    public String id() {
        return settings.model();
    }

    URI endpoint() {
        return endpoint;
    }

    @Override
    public ModelReply complete(BoundedText document) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new ModelUnavailableException("no model credential is configured");
        }
        String body = requestBody(document);
        Map<String, String> headers = Map.of(
                "x-goog-api-key", settings.apiKey(),
                "Content-Type", "application/json",
                "User-Agent", "anvil-extract/1.0");
        for (int attempt = 0;; attempt++) {
            Response r;
            try {
                r = transport.post(endpoint, headers, body);
            } catch (IOException e) {
                if (attempt < settings.maxRetries()) {
                    pause(backoff(attempt, Optional.empty()));
                    continue;
                }
                throw new ModelUnavailableException("model call failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelUnavailableException("model call interrupted", e);
            }
            if (r.status() == 200) {
                return reply(r.body());
            }
            if (RETRYABLE.contains(r.status()) && attempt < settings.maxRetries()) {
                pause(backoff(attempt, r.retryAfter()));
                continue;
            }
            // Any other code is a configuration fault (key, model, quota); neither is the document's.
            throw new ModelUnavailableException("model call failed with HTTP " + r.status());
        }
    }

    String requestBody(BoundedText document) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("responseMimeType", "application/json");
        generation.put("responseSchema", SCHEMA);
        generation.put("maxOutputTokens", settings.maxTokens());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("systemInstruction", Map.of("parts", List.of(Map.of("text", Prompt.SYSTEM))));
        req.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", Prompt.user(document))))));
        req.put("generationConfig", generation);
        try {
            return JSON.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("request body", e);
        }
    }

    static ModelReply reply(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ModelUnavailableException("model returned a body that isn't JSON", e);
        }
        if (root.path("promptFeedback").hasNonNull("blockReason")) {
            return new ModelReply("", "refusal");
        }
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            return new ModelReply("", "no_candidate");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                text.append(part.path("text").asText());
            }
        }
        String finish = candidate.path("finishReason").asText("");
        String stop = switch (finish) {
            case "STOP" -> "end_turn";
            case "MAX_TOKENS" -> "max_tokens";
            default -> REFUSALS.contains(finish) ? "refusal" : finish.toLowerCase(Locale.ROOT);
        };
        return new ModelReply(text.toString(), stop);
    }

    static Duration backoff(int attempt, Optional<String> retryAfter) {
        Duration d = retryAfter.flatMap(GeminiExtractionModel::seconds)
                .orElse(Duration.ofSeconds(2L << Math.min(attempt, 5)));
        return d.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : d;
    }

    private static Optional<Duration> seconds(String header) {
        try {
            long s = Long.parseLong(header.trim());
            return s >= 0 ? Optional.of(Duration.ofSeconds(s)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void pause(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("model call interrupted", e);
        }
    }

    private static Transport httpTransport(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, headers, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.headers().firstValue("Retry-After"), r.body());
        };
    }
}
