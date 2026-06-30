package com.example.recruitmentbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integration")
public record IntegrationApiProperties(
        boolean enabled,
        String apiKey
) {
    public boolean requiresApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
