package com.example.recruitmentbot.jobposting.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "facebook.job-posting")
public record FacebookJobPostingProperties(
        boolean enabled,
        @NotBlank String pageId,
        @NotBlank String pageAccessToken,
        @NotBlank String graphApiBaseUrl,
        @NotBlank String repostCron
) {
}
