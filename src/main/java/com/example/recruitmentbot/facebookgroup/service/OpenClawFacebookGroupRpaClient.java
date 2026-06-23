package com.example.recruitmentbot.facebookgroup.service;

import com.example.recruitmentbot.config.FacebookGroupPostingProperties;
import com.example.recruitmentbot.openclaw.dto.OpenClawToolInvokeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Service
public class OpenClawFacebookGroupRpaClient implements FacebookGroupRpaClient {

    private final RestTemplate restTemplate;
    private final FacebookGroupPostingProperties properties;

    public OpenClawFacebookGroupRpaClient(
            RestTemplateBuilder builder,
            FacebookGroupPostingProperties properties
    ) {
        this.properties = properties;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(properties.effectiveRpaConnectTimeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(properties.effectiveRpaReadTimeoutSeconds()))
                .build();
    }

    @Override
    public FacebookGroupRpaResult postToGroup(FacebookGroupRpaRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scriptName", request.scriptName());
        args.put("jobDescriptionId", request.jobDescriptionId());
        args.put("groupId", request.groupId());
        args.put("groupName", request.groupName());
        args.put("groupReference", request.groupReference());
        args.put("content", request.generatedContent());

        OpenClawToolInvokeRequest toolRequest = new OpenClawToolInvokeRequest(
                request.scriptName(),
                "post",
                args,
                "facebook-group-post:" + request.jobDescriptionId() + ":" + request.groupId()
        );
        JsonNode response = invokeRpaTool(toolRequest);

        boolean success = response != null
                && (response.path("success").asBoolean(false)
                || response.path("ok").asBoolean(false)
                || response.path("result").path("success").asBoolean(false)
                || response.path("result").path("ok").asBoolean(false));
        String message = firstText(
                response == null ? null : response.path("message"),
                response == null ? null : response.path("error"),
                response == null ? null : response.path("result").path("message"),
                response == null ? null : response.path("result").path("error")
        );
        String postUrl = firstText(
                response == null ? null : response.path("postUrl"),
                response == null ? null : response.path("result").path("postUrl")
        );

        return new FacebookGroupRpaResult(
                success,
                StringUtils.hasText(message) ? message : (success ? "OpenClaw RPA completed" : "OpenClaw RPA did not report success"),
                postUrl,
                response == null ? null : response.toString()
        );
    }

    private JsonNode invokeRpaTool(OpenClawToolInvokeRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(properties.rpaAuthToken())) {
            headers.setBearerAuth(properties.rpaAuthToken().trim());
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    properties.effectiveRpaToolsInvokeUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    JsonNode.class
            );
            return response.getBody();
        } catch (RestClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            throw new IllegalStateException("Facebook Group RPA tool failed: " + body, exception);
        }
    }

    private String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }
}
