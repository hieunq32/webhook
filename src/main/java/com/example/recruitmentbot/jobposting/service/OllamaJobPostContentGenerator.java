package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.config.OllamaProperties;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
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
public class OllamaJobPostContentGenerator implements JobPostContentGenerator {

    private static final Logger log = LoggerFactory.getLogger(OllamaJobPostContentGenerator.class);

    private final RestTemplate restTemplate;
    private final OllamaProperties ollamaProperties;

    public OllamaJobPostContentGenerator(
            @Qualifier("ollamaRestTemplate") RestTemplate restTemplate,
            OllamaProperties ollamaProperties
    ) {
        this.restTemplate = restTemplate;
        this.ollamaProperties = ollamaProperties;
    }

    @Override
    public String generatePost(JobDescription jobDescription) {
        validateConfiguration();

        String prompt = buildPrompt(jobDescription);
        String preferredModel = ollamaProperties.jobPostModel();
        String fallbackModel = ollamaProperties.model();

        try {
            return generateWithEndpoint(prompt, buildGenerateUrl(), preferredModel);
        } catch (RestClientResponseException exception) {
            if (shouldRetryOnInvalidEndpoint(exception)) {
                log.warn("Ollama /api/generate endpoint is unavailable. Falling back to /api/chat.");
                return generateWithEndpoint(prompt, buildChatUrl(), preferredModel);
            }

            if (shouldRetryOnMissingModel(exception) && StringUtils.hasText(fallbackModel) && !preferredModel.equals(fallbackModel)) {
                log.warn("Ollama job-post model '{}' is unavailable. Retrying with fallback model '{}'.", preferredModel, fallbackModel);
                try {
                    return generateWithEndpoint(prompt, buildGenerateUrl(), fallbackModel);
                } catch (RestClientResponseException secondException) {
                    if (shouldRetryOnInvalidEndpoint(secondException)) {
                        return generateWithEndpoint(prompt, buildChatUrl(), fallbackModel);
                    }
                    throw secondException;
                }
            }

            log.error("Ollama generate API returned an error. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("Ollama generate API call failed", exception);
        } catch (RestClientException exception) {
            log.error("Ollama generate API call failed before a response was received", exception);
            throw new IllegalStateException("Ollama generate API call failed", exception);
        }
    }

    private String generateWithEndpoint(String prompt, String endpointUrl, String model) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        boolean useChatEndpoint = endpointUrl.endsWith("/api/chat");
        Object request = useChatEndpoint
                ? new OllamaChatRequest(model, prompt, false)
                : new OllamaGenerateRequest(model, prompt, false);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                endpointUrl,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                JsonNode.class
        );

        JsonNode responseBody = response.getBody();
        String generatedContent = useChatEndpoint
                ? extractChatText(responseBody)
                : extractGenerateText(responseBody);

        if (!StringUtils.hasText(generatedContent)) {
            throw new IllegalStateException("Ollama returned empty generated content");
        }

        log.info("Generated Facebook job post content successfully via Ollama endpoint={}", endpointUrl);
        return generatedContent.trim();
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(ollamaProperties.baseUrl())
                || !StringUtils.hasText(ollamaProperties.generateApiPath())
                || !StringUtils.hasText(ollamaProperties.jobPostModel())
                || !StringUtils.hasText(ollamaProperties.jobPostPromptTemplate())
                || !StringUtils.hasText(ollamaProperties.model())) {
            throw new IllegalStateException("Ollama job post generation configuration is incomplete");
        }
    }

    private String buildGenerateUrl() {
        String baseUrl = trimTrailingSlash(ollamaProperties.baseUrl());
        String apiPath = ollamaProperties.generateApiPath().startsWith("/")
                ? ollamaProperties.generateApiPath()
                : "/" + ollamaProperties.generateApiPath();
        return baseUrl + apiPath;
    }

    private String buildChatUrl() {
        return trimTrailingSlash(ollamaProperties.baseUrl()) + "/api/chat";
    }

    private String buildPrompt(JobDescription jobDescription) {
        return ollamaProperties.jobPostPromptTemplate()
                .replace("{{title}}", safe(jobDescription.getTitle()))
                .replace("{{description}}", safe(jobDescription.getDescription()))
                .replace("{{requirements}}", safe(jobDescription.getRequirements()))
                .replace("{{salary}}", safe(jobDescription.getSalary()))
                .replace("{{location}}", safe(jobDescription.getLocation()))
                .replace("{{workType}}", safe(jobDescription.getWorkType().name()))
                .replace("{{status}}", safe(jobDescription.getStatus().name()));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String extractGenerateText(JsonNode responseBody) {
        if (responseBody == null) {
            return null;
        }
        return responseBody.path("response").asText(null);
    }

    private String extractChatText(JsonNode responseBody) {
        if (responseBody == null) {
            return null;
        }
        return responseBody.path("message").path("content").asText(null);
    }

    private boolean shouldRetryOnInvalidEndpoint(RestClientResponseException exception) {
        int status = exception.getRawStatusCode();
        return status == 404 || status == 405;
    }

    private boolean shouldRetryOnMissingModel(RestClientResponseException exception) {
        String responseBody = exception.getResponseBodyAsString();
        if (!StringUtils.hasText(responseBody)) {
            return false;
        }
        String lowerBody = responseBody.toLowerCase(Locale.ROOT);
        return lowerBody.contains("not found") && lowerBody.contains("model");
    }

    private record OllamaGenerateRequest(
            String model,
            String prompt,
            boolean stream
    ) {
    }

    private record OllamaChatRequest(
            String model,
            OllamaMessage[] messages,
            boolean stream
    ) {
        public OllamaChatRequest(String model, String prompt, boolean stream) {
            this(model, new OllamaMessage[]{new OllamaMessage("user", prompt)}, stream);
        }
    }

    private record OllamaMessage(String role, String content) {
    }
}

