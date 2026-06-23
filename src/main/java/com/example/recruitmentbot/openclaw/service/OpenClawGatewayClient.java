package com.example.recruitmentbot.openclaw.service;

import com.example.recruitmentbot.config.OpenClawProperties;
import com.example.recruitmentbot.openclaw.dto.OpenClawResponsesRequest;
import com.example.recruitmentbot.openclaw.dto.OpenClawToolInvokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
public class OpenClawGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(OpenClawGatewayClient.class);

    private final RestTemplate restTemplate;
    private final OpenClawProperties properties;
    private final OpenClawAuthTokenResolver authTokenResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenClawGatewayClient(
            RestTemplateBuilder builder,
            OpenClawProperties properties,
            OpenClawAuthTokenResolver authTokenResolver
    ) {
        this.properties = properties;
        this.authTokenResolver = authTokenResolver;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(Math.max(properties.connectTimeoutSeconds(), 1)))
                .setReadTimeout(Duration.ofSeconds(Math.max(properties.readTimeoutSeconds(), 1)))
                .build();
    }

    public JsonNode checkHealth() {
        validateConfiguration();
        JsonNode response = runConnectivityTest();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", true);
        result.put("baseUrl", properties.baseUrl());
        result.put("healthUrl", properties.healthUrl());
        result.put("responsesUrl", properties.responsesUrl());
        result.set("probe", response);
        return result;
    }

    public JsonNode runConnectivityTest() {
        return runResponse(new OpenClawResponsesRequest(
                "Reply with exactly OPENCLAW_SPRING_OK and nothing else.",
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    public String generateRecruitmentReply(String message, String senderId) {
        JsonNode response = runResponse(new OpenClawResponsesRequest(
                message,
                """
                You are an AI recruitment assistant orchestrated through OpenClaw.
                Reply naturally in Vietnamese when the user writes Vietnamese.
                Reply naturally in English when the user writes English.
                Stay strictly within recruitment scope: job roles, skills, applications, CVs, interviews, compensation, schedules, hiring process.
                If the user asks outside recruitment scope, politely redirect them back to recruitment topics.
                Keep the answer concise, professional, and directly usable as a Messenger reply.
                Return only the final assistant reply text.
                """,
                senderId,
                null,
                properties.defaultAgentId(),
                null,
                "recruitment:" + sanitizeSessionKey(senderId)
        ));
        String extracted = extractOutputText(response);
        if (!StringUtils.hasText(extracted)) {
            throw new IllegalStateException("OpenClaw recruitment reply did not contain assistant text");
        }
        return extracted.trim();
    }

    public JsonNode runResponse(OpenClawResponsesRequest request) {
        validateConfiguration();
        if (request == null || !StringUtils.hasText(request.input())) {
            throw new IllegalArgumentException("OpenClaw response request must include input");
        }

        HttpHeaders headers = buildJsonHeaders();
        headers.set("x-openclaw-agent-id", firstNonBlank(request.agentId(), properties.defaultAgentId(), "main"));
        if (StringUtils.hasText(request.backendModel())) {
            headers.set("x-openclaw-model", request.backendModel().trim());
        }
        if (StringUtils.hasText(request.sessionKey())) {
            headers.set("x-openclaw-session-key", request.sessionKey().trim());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", firstNonBlank(request.model(), properties.defaultModel(), "openclaw/default"));
        body.put("input", request.input());
        if (StringUtils.hasText(request.instructions())) {
            body.put("instructions", request.instructions().trim());
        }
        if (StringUtils.hasText(request.user())) {
            body.put("user", request.user().trim());
        }
        body.put("stream", false);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    properties.responsesUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    JsonNode.class
            );
            return response.getBody();
        } catch (RestClientResponseException exception) {
            log.error("OpenClaw responses API failed. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("OpenClaw responses API failed", exception);
        } catch (RestClientException exception) {
            log.error("OpenClaw responses API failed before a response was received", exception);
            throw new IllegalStateException("OpenClaw responses API failed", exception);
        }
    }

    public JsonNode invokeTool(OpenClawToolInvokeRequest request) {
        validateConfiguration();
        if (request == null || !StringUtils.hasText(request.tool())) {
            throw new IllegalArgumentException("OpenClaw tool invoke request must include tool");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", request.tool().trim());
        if (StringUtils.hasText(request.action())) {
            body.put("action", request.action().trim());
        }
        body.put("args", request.args() == null ? Map.of() : request.args());
        if (StringUtils.hasText(request.sessionKey())) {
            body.put("sessionKey", request.sessionKey().trim());
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    properties.toolsInvokeUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(body, buildJsonHeaders()),
                    JsonNode.class
            );
            return response.getBody();
        } catch (RestClientResponseException exception) {
            log.error("OpenClaw tools invoke API failed. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("OpenClaw tools invoke API failed", exception);
        } catch (RestClientException exception) {
            log.error("OpenClaw tools invoke API failed before a response was received", exception);
            throw new IllegalStateException("OpenClaw tools invoke API failed", exception);
        }
    }

    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = authTokenResolver.resolveToken();
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token.trim());
        }
        return headers;
    }

    private void validateConfiguration() {
        if (!properties.enabled()) {
            throw new IllegalStateException("OpenClaw integration is disabled");
        }
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("OpenClaw base URL is not configured");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String extractOutputText(JsonNode response) {
        if (response == null) {
            return null;
        }

        JsonNode output = response.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode contentItem : content) {
                    String text = contentItem.path("text").asText(null);
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private String sanitizeSessionKey(String senderId) {
        if (!StringUtils.hasText(senderId)) {
            return "anonymous";
        }
        return senderId.replaceAll("[^a-zA-Z0-9:_-]", "_");
    }
}
