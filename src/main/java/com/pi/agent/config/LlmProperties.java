package com.pi.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for LLM client.
 */
@ConfigurationProperties(prefix = "pi.llm")
public record LlmProperties(
    String baseUrl,
    String apiKey,
    String defaultProvider,
    String defaultModel
) {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    public static final String DEFAULT_PROVIDER = "openai";
    public static final String DEFAULT_MODEL = "gpt-4.1-nano";
    
    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (defaultProvider == null || defaultProvider.isBlank()) {
            defaultProvider = DEFAULT_PROVIDER;
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = DEFAULT_MODEL;
        }
    }
}
