package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.OpenAiProperties;
import com.example.recruitmentbot.dto.openai.OpenAiResponsesRequest;
import com.example.recruitmentbot.dto.openai.OpenAiResponsesRequest.InputMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private final RestTemplate restTemplate;
    private final OpenAiProperties openAiProperties;

    public OpenAiService(RestTemplate restTemplate, OpenAiProperties openAiProperties) {
        this.restTemplate = restTemplate;
        this.openAiProperties = openAiProperties;
    }

    public String generateRecruitmentReply(String candidateMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiProperties.apiKey());

        OpenAiResponsesRequest request = new OpenAiResponsesRequest(
                openAiProperties.model(),
                false,
                List.of(
                        new InputMessage("system", openAiProperties.systemPrompt()),
                        new InputMessage("user", candidateMessage)
                )
        );

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    openAiProperties.responsesUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    JsonNode.class
            );

            String replyText = extractReplyText(response.getBody());
            if (!StringUtils.hasText(replyText)) {
                throw new IllegalStateException("OpenAI returned a response without assistant text");
            }

            log.info("Generated OpenAI recruitment reply successfully");
            return replyText;
        } catch (RestClientResponseException exception) {
            log.error("OpenAI API returned an error. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("OpenAI API call failed", exception);
        } catch (RestClientException exception) {
            log.error("OpenAI API call failed before a response was received", exception);
            throw new IllegalStateException("OpenAI API call failed", exception);
        }
    }

    private String extractReplyText(JsonNode responseBody) {
        if (responseBody == null || !responseBody.has("output")) {
            return null;
        }

        for (JsonNode outputItem : responseBody.path("output")) {
            if (!"message".equals(outputItem.path("type").asText())) {
                continue;
            }

            for (JsonNode contentItem : outputItem.path("content")) {
                if ("output_text".equals(contentItem.path("type").asText())
                        && StringUtils.hasText(contentItem.path("text").asText())) {
                    return contentItem.path("text").asText();
                }
            }
        }

        return null;
    }
}
