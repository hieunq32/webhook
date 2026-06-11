package com.example.recruitmentbot.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "facebook")
public record FacebookProperties(
        @NotBlank String verifyToken,
        @NotBlank String pageAccessToken,
        @NotBlank String sendApiUrl
) {
}
