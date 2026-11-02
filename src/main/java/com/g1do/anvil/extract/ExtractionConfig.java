package com.g1do.anvil.extract;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the extraction model from {@code anvil.extraction.*} (record 0022).
 * The credential is read from {@code ANVIL_MODEL_API_KEY}; without it the
 * app still starts and extraction answers 503 without calling a model.
 */
@Configuration(proxyBeanMethods = false)
public class ExtractionConfig {

    /** Which API the extraction model speaks. */
    public enum Provider {
        /** The Gemini API's generateContent. */
        GEMINI,
        /** An Anthropic-compatible Messages API, such as Anthropic's or OpenCode Go's. */
        ANTHROPIC
    }

    /** Builds the adapter for a provider; the service and the harness both use it. */
    static ExtractionModel model(Provider provider, String baseUrl, String model, String apiKey,
            AnthropicExtractionModel.Auth auth, String sessionHeader, Duration timeout, long maxTokens,
            int maxRetries) {
        return switch (provider) {
            case GEMINI -> new GeminiExtractionModel(
                    new GeminiExtractionModel.Settings(baseUrl, model, apiKey, timeout, maxTokens, maxRetries));
            case ANTHROPIC -> new AnthropicExtractionModel(new AnthropicExtractionModel.Settings(baseUrl, model,
                    apiKey, auth, sessionHeader, timeout, maxTokens));
        };
    }

    @Bean
    ExtractionModel extractionModel(
            @Value("${anvil.extraction.provider:GEMINI}") Provider provider,
            @Value("${anvil.extraction.base-url}") String baseUrl,
            @Value("${anvil.extraction.model}") String model,
            @Value("${anvil.extraction.api-key:}") String apiKey,
            @Value("${anvil.extraction.auth:BEARER}") AnthropicExtractionModel.Auth auth,
            @Value("${anvil.extraction.session-header:}") String sessionHeader,
            @Value("${anvil.extraction.timeout:30s}") Duration timeout,
            @Value("${anvil.extraction.max-tokens:8192}") long maxTokens) {
        return model(provider, baseUrl, model, apiKey, auth, sessionHeader, timeout, maxTokens, 2);
    }

    @Bean
    Extractor extractor(ExtractionModel model) {
        return new Extractor(model);
    }
}
