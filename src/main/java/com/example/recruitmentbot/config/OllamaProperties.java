package com.example.recruitmentbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        String systemPrompt,
        String generateApiPath,
        String jobPostModel,
        String jobPostPromptTemplate
) {
}
