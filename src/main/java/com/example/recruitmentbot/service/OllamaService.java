package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.OllamaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    private final RestTemplate restTemplate;
    private final OllamaProperties ollamaProperties;

    public OllamaService(@Qualifier("ollamaRestTemplate") RestTemplate restTemplate,
                         OllamaProperties ollamaProperties) {
        this.restTemplate = restTemplate;
        this.ollamaProperties = ollamaProperties;
    }

    public String generateRecruitmentReply(String candidateMessage) {
        return generate(ollamaProperties.systemPrompt(), candidateMessage);
    }

    public String generate(String systemPrompt, String userPrompt) {
        validateOllamaConfiguration();
        if (!StringUtils.hasText(systemPrompt) || !StringUtils.hasText(userPrompt)) {
            throw new IllegalStateException("Ollama prompt content is incomplete");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        OllamaChatRequest request = new OllamaChatRequest(
                ollamaProperties.model(),
                false,
                List.of(
                        new OllamaMessage("system", systemPrompt),
                        new OllamaMessage("user", userPrompt)
                )
        );

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    buildChatUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    JsonNode.class
            );

            String replyText = extractReplyText(response.getBody());
            if (!StringUtils.hasText(replyText)) {
                throw new IllegalStateException("Ollama returned a response without assistant text");
            }

            log.info("Generated Ollama recruitment reply successfully");
            return replyText;
        } catch (RestClientResponseException exception) {
            log.error("Ollama API returned an error. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("Ollama API call failed", exception);
        } catch (RestClientException exception) {
            log.error("Ollama API call failed before a response was received", exception);
            throw new IllegalStateException("Ollama API call failed", exception);
        }
    }

    private void validateOllamaConfiguration() {
        if (!StringUtils.hasText(ollamaProperties.baseUrl())
                || !StringUtils.hasText(ollamaProperties.model())
                || !StringUtils.hasText(ollamaProperties.systemPrompt())) {
            throw new IllegalStateException("OLLAMA mode is enabled but Ollama configuration is incomplete");
        }
    }

    private String buildChatUrl() {
        String baseUrl = ollamaProperties.baseUrl().trim();
        return baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
    }

    private String extractReplyText(JsonNode responseBody) {
        if (responseBody == null) {
            return null;
        }
        return responseBody.path("message").path("content").asText(null);
    }

    private record OllamaChatRequest(
            String model,
            boolean stream,
            List<OllamaMessage> messages
    ) {
    }

    private record OllamaMessage(String role, String content) {
    }
}
