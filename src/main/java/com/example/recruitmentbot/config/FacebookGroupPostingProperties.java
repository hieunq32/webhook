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
        int rpaReadTimeoutSeconds,
        boolean candidateCtaEnabled,
        String candidateFanpageName,
        String candidateFanpageUrl,
        String candidateCtaTemplate
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

    public String effectiveCandidateFanpageName() {
        return candidateFanpageName == null || candidateFanpageName.isBlank()
                ? "Test VCS"
                : candidateFanpageName.trim();
    }

    public String effectiveCandidateFanpageUrl() {
        return candidateFanpageUrl == null || candidateFanpageUrl.isBlank()
                ? null
                : candidateFanpageUrl.trim();
    }

    public String effectiveCandidateCtaTemplate() {
        if (candidateCtaTemplate == null || candidateCtaTemplate.isBlank()) {
            return """
                    Để biết thông tin chi tiết và ứng tuyển, vui lòng nhắn tin trực tiếp fanpage {{fanpageName}}{{fanpageUrlLine}}.
                    Vui lòng không inbox tài khoản cá nhân của HR để tránh bỏ sót thông tin ứng tuyển.
                    """;
        }
        return candidateCtaTemplate.trim();
    }
}
