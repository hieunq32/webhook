package com.example.recruitmentbot.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        @NotBlank String mode,
        String apiKey,
        String model,
        String responsesUrl,
        String systemPrompt
) {

    public boolean isMockMode() {
        return "MOCK".equalsIgnoreCase(mode);
    }

    public boolean isOpenAiMode() {
        return "OPENAI".equalsIgnoreCase(mode);
    }

    public boolean isOllamaMode() {
        return "OLLAMA".equalsIgnoreCase(mode);
    }
}
