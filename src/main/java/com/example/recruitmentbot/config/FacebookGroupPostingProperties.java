package com.example.recruitmentbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "facebook.group-posting")
public record FacebookGroupPostingProperties(
        boolean enabled,
        boolean schedulerEnabled,
        String schedulerCron,
        String openClawScriptName,
        int delayMinSeconds,
        int delayMaxSeconds,
        int maxPostsPerDayPerGroup,
        int retryCount,
        String rpaToolsInvokeUrl,
        String rpaAuthToken,
        int rpaConnectTimeoutSeconds,
        int rpaReadTimeoutSeconds
) {
    public int effectiveDelayMinSeconds() {
        return Math.max(delayMinSeconds, 0);
    }

    public int effectiveDelayMaxSeconds() {
        return Math.max(delayMaxSeconds, effectiveDelayMinSeconds());
    }

    public int effectiveMaxPostsPerDayPerGroup() {
        return Math.max(maxPostsPerDayPerGroup, 1);
    }

    public int effectiveRetryCount() {
        return Math.max(retryCount, 0);
    }

    public String effectiveOpenClawScriptName() {
        return openClawScriptName == null || openClawScriptName.isBlank()
                ? "facebookGroupPost"
                : openClawScriptName.trim();
    }

    public String effectiveRpaToolsInvokeUrl() {
        return rpaToolsInvokeUrl == null || rpaToolsInvokeUrl.isBlank() ? null : rpaToolsInvokeUrl.trim();
    }

    public int effectiveRpaConnectTimeoutSeconds() {
        return Math.max(rpaConnectTimeoutSeconds, 1);
    }

    public int effectiveRpaReadTimeoutSeconds() {
        return Math.max(rpaReadTimeoutSeconds, 30);
    }
}
