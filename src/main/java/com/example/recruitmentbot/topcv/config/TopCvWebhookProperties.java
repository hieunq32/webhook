package com.example.recruitmentbot.topcv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "topcv.webhook")
public record TopCvWebhookProperties(
        boolean enabled,
        boolean requireApiKey,
        String apiKey,
        String apiKeyHeaderName,
        String authQueryParamName
) {
}
