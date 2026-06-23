package com.example.recruitmentbot.facebookgroup.service;

import com.example.recruitmentbot.config.FacebookGroupPostingProperties;
import com.example.recruitmentbot.config.OpenClawProperties;
import com.example.recruitmentbot.openclaw.dto.OpenClawToolInvokeRequest;
import com.example.recruitmentbot.openclaw.service.OpenClawAuthTokenResolver;
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
    private final OpenClawProperties openClawProperties;
    private final OpenClawAuthTokenResolver authTokenResolver;

    public OpenClawFacebookGroupRpaClient(
            RestTemplateBuilder builder,
            FacebookGroupPostingProperties properties,
            OpenClawProperties openClawProperties,
            OpenClawAuthTokenResolver authTokenResolver
    ) {
        this.properties = properties;
        this.openClawProperties = openClawProperties;
        this.authTokenResolver = authTokenResolver;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(Math.max(openClawProperties.connectTimeoutSeconds(), properties.effectiveRpaConnectTimeoutSeconds())))
                .setReadTimeout(Duration.ofSeconds(Math.max(openClawProperties.readTimeoutSeconds(), properties.effectiveRpaReadTimeoutSeconds())))
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

        JsonNode details = response == null ? null : response.path("result").path("details");
        JsonNode nestedResult = details == null ? null : details.path("result");
        boolean success = response != null
                && (booleanValue(details, "success")
                || booleanValue(details, "ok")
                || booleanValue(nestedResult, "success")
                || booleanValue(nestedResult, "ok")
                || booleanValue(response.path("details"), "success")
                || booleanValue(response.path("details"), "ok"));
        String message = firstText(
                nestedResult == null ? null : nestedResult.path("message"),
                nestedResult == null ? null : nestedResult.path("error"),
                details == null ? null : details.path("message"),
                details == null ? null : details.path("error"),
                response == null ? null : response.path("details").path("message"),
                response == null ? null : response.path("details").path("error"),
                response == null ? null : response.path("message"),
                response == null ? null : response.path("error")
        );
        String postUrl = firstText(
                nestedResult == null ? null : nestedResult.path("postUrl"),
                details == null ? null : details.path("postUrl"),
                response == null ? null : response.path("details").path("postUrl"),
                response == null ? null : response.path("postUrl")
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
        String token = StringUtils.hasText(properties.rpaAuthToken())
                ? properties.rpaAuthToken().trim()
                : authTokenResolver.resolveToken();
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token.trim());
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    effectiveToolsInvokeUrl(),
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

    private String effectiveToolsInvokeUrl() {
        String overrideUrl = properties.effectiveRpaToolsInvokeUrl();
        return StringUtils.hasText(overrideUrl) ? overrideUrl : openClawProperties.toolsInvokeUrl();
    }

    private boolean booleanValue(JsonNode node, String fieldName) {
        return node != null && !node.isMissingNode() && node.path(fieldName).asBoolean(false);
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
