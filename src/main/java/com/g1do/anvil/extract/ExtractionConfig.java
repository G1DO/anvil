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

    @Bean
    ExtractionModel extractionModel(
            @Value("${anvil.extraction.base-url}") String baseUrl,
            @Value("${anvil.extraction.model}") String model,
            @Value("${anvil.extraction.api-key:}") String apiKey,
            @Value("${anvil.extraction.auth:BEARER}") AnthropicExtractionModel.Auth auth,
            @Value("${anvil.extraction.session-header:}") String sessionHeader,
            @Value("${anvil.extraction.timeout:30s}") Duration timeout,
            @Value("${anvil.extraction.max-tokens:4096}") long maxTokens) {
        return new AnthropicExtractionModel(new AnthropicExtractionModel.Settings(baseUrl, model, apiKey, auth,
                sessionHeader, timeout, maxTokens));
    }

    @Bean
    Extractor extractor(ExtractionModel model) {
        return new Extractor(model);
    }
}
