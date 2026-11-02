package com.g1do.anvil.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ExtractionModel} over the DeepSeek chat completions API (#37,
 * record 0022).
 *
 * <p>The base URL, model and credential come from configuration. The base URL
 * must be HTTPS and is operator configuration, never tenant input. The key is
 * sent as a bearer token and is never logged or stored.
 *
 * <p>Each call is one stateless request: a system message, one user message
 * holding the bounded document, JSON output mode, thinking off and
 * temperature 0. No conversation or tenant identifier is sent. The reply is
 * untrusted: the whole exchange, body included, must finish within the
 * timeout, and a body over {@link #MAX_REPLY_BYTES} is cut off and refused.
 *
 * <p>The finish reason is mapped onto the stop reasons {@link Extractor}
 * decides on: {@code stop} is {@code end_turn}, {@code length} is
 * {@code max_tokens}, {@code content_filter} is {@code refusal}. A reply that
 * says the provider couldn't serve it ({@code insufficient_system_resource},
 * {@code aborted}, or no choice at all) is an outage, not poison: it throws
 * {@link ModelUnavailableException} so nothing is stored. Any other finish
 * reason passes through and is quarantined as malformed.
 */
public final class DeepSeekExtractionModel implements ExtractionModel {

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

    /** A reply body larger than this is refused; a 4096-token reply is a few tens of KB. */
    static final int MAX_REPLY_BYTES = 1 << 20;

    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9.\\-]{0,63}");
    /** Rate limit, server error and overload may pass on retry; other codes are a configuration fault. */
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504);
    /** Finish reasons that mean the provider couldn't serve the request. */
    private static final Set<String> OUTAGES = Set.of("insufficient_system_resource", "aborted");
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Settings settings;
    private final URI endpoint;
    private final Transport transport;
    private final Sleeper sleeper;

    public DeepSeekExtractionModel(Settings settings) {
        this(settings, httpTransport(settings.timeout()), d -> Thread.sleep(d.toMillis()));
    }

    DeepSeekExtractionModel(Settings settings, Transport transport, Sleeper sleeper) {
        URI base = URI.create(settings.baseUrl());
        if (!"https".equals(base.getScheme()) || base.getHost() == null) {
            throw new IllegalArgumentException("extraction base URL must be https: " + settings.baseUrl());
        }
        if (settings.model() == null || !MODEL_ID.matcher(settings.model()).matches()) {
            throw new IllegalArgumentException("not a model id: " + settings.model());
        }
        this.settings = settings;
        this.endpoint = URI.create(settings.baseUrl().replaceAll("/+$", "") + "/chat/completions");
        this.transport = transport;
        this.sleeper = sleeper;
    }

    @Override
    public String id() {
        return settings.model();
    }

    @Override
    public ModelReply complete(BoundedText document) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new ModelUnavailableException("no model credential is configured");
        }
        String body = requestBody(document);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + settings.apiKey().strip(),
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
            // 401 bad key, 402 no balance, 400/422 bad request, or the retry budget spent; none is the document's.
            throw new ModelUnavailableException("model call failed with HTTP " + r.status());
        }
    }

    String requestBody(BoundedText document) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", settings.model());
        req.put("messages", List.of(
                Map.of("role", "system", "content", Prompt.SYSTEM),
                Map.of("role", "user", "content", Prompt.user(document))));
        req.put("response_format", Map.of("type", "json_object"));
        req.put("thinking", Map.of("type", "disabled"));
        req.put("temperature", 0);
        req.put("max_tokens", settings.maxTokens());
        req.put("stream", false);
        try {
            return JSON.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("request body", e);
        }
    }

    static ModelReply reply(String body) {
        JsonNode root;
        try {
            // While a request waits, the API sends blank lines before the body; the parser skips them.
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ModelUnavailableException("model returned a body that isn't JSON", e);
        }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        if (choice == null || choice.isMissingNode()) {
            throw new ModelUnavailableException("model returned no choice");
        }
        String finish = choice.path("finish_reason").asText("");
        if (OUTAGES.contains(finish)) {
            throw new ModelUnavailableException("model couldn't serve the request: " + finish);
        }
        String text = choice.path("message").path("content").asText("");
        String stop = switch (finish) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> finish.toLowerCase(Locale.ROOT);
        };
        return new ModelReply(text, stop, usage(root.path("usage")));
    }

    /**
     * The billed tokens: {@code prompt_cache_hit_tokens},
     * {@code prompt_cache_miss_tokens} and {@code completion_tokens}. Null when
     * any is missing or not a non-negative integer, so a cost is never made up.
     */
    static ModelReply.Usage usage(JsonNode u) {
        JsonNode hit = u.path("prompt_cache_hit_tokens");
        JsonNode miss = u.path("prompt_cache_miss_tokens");
        JsonNode out = u.path("completion_tokens");
        if (!hit.canConvertToLong() || !miss.canConvertToLong() || !out.canConvertToLong()
                || !hit.isIntegralNumber() || !miss.isIntegralNumber() || !out.isIntegralNumber()
                || hit.asLong() < 0 || miss.asLong() < 0 || out.asLong() < 0) {
            return null;
        }
        return new ModelReply.Usage(hit.asLong(), miss.asLong(), out.asLong());
    }

    /** The server's {@code Retry-After} seconds when given, else 2, 4, 8 … seconds; at most a minute. */
    static Duration backoff(int attempt, Optional<String> retryAfter) {
        Duration d = retryAfter.flatMap(DeepSeekExtractionModel::seconds)
                .orElse(Duration.ofSeconds(2L << Math.min(attempt, 5)));
        return d.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : d;
    }

    private static Optional<Duration> seconds(String header) {
        try {
            long s = Long.parseLong(header.strip());
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

    /**
     * The JDK client with one deadline for the whole exchange. A request
     * timeout alone stops counting once headers arrive, and the API sends
     * headers early and then blank lines while a request waits, so the body
     * read is bounded here too.
     */
    static Transport httpTransport(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, headers, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(b::header);
            CompletableFuture<HttpResponse<String>> call = client.sendAsync(b.build(),
                    info -> new CappedBody(MAX_REPLY_BYTES));
            try {
                HttpResponse<String> r = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(r.statusCode(), r.headers().firstValue("Retry-After"), r.body());
            } catch (TimeoutException e) {
                call.cancel(true);
                throw new HttpTimeoutException("no complete reply within " + timeout);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException("model call failed", e.getCause());
            }
        };
    }

    /** Collects a UTF-8 body and cancels the exchange once it passes {@code limit} bytes. */
    static final class CappedBody implements HttpResponse.BodySubscriber<String> {

        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buf : items) {
                if (bytes.size() + buf.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("reply larger than " + limit + " bytes"));
                    return;
                }
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
