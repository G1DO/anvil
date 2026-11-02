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
            @Value("${anvil.extraction.timeout:60s}") Duration timeout,
            @Value("${anvil.extraction.max-tokens:4096}") long maxTokens) {
        return new DeepSeekExtractionModel(
                new DeepSeekExtractionModel.Settings(baseUrl, model, apiKey, timeout, maxTokens, 2));
    }

    @Bean
    Extractor extractor(ExtractionModel model) {
        return new Extractor(model);
    }
}
