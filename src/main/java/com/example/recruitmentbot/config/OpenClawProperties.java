package com.example.recruitmentbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openclaw")
public record OpenClawProperties(
        boolean enabled,
        String baseUrl,
        String gatewayToken,
        String wslDistro,
        String defaultModel,
        String defaultAgentId,
        String healthPath,
        String responsesPath,
        String toolsInvokePath,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int tokenResolveTimeoutSeconds
) {

    public String healthUrl() {
        return normalizeBaseUrl(baseUrl) + normalizePath(healthPath, "/");
    }

    public String responsesUrl() {
        return normalizeBaseUrl(baseUrl) + normalizePath(responsesPath, "/v1/responses");
    }

    public String toolsInvokeUrl() {
        return normalizeBaseUrl(baseUrl) + normalizePath(toolsInvokePath, "/tools/invoke");
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:18789";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizePath(String value, String defaultValue) {
        String effectiveValue = (value == null || value.isBlank()) ? defaultValue : value.trim();
        return effectiveValue.startsWith("/") ? effectiveValue : "/" + effectiveValue;
    }
}
