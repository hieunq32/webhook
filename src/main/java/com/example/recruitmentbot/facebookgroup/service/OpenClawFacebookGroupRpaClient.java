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
    public FacebookGroupRpaSessionStatus getSessionStatus() {
        return toSessionStatus(invokeRpaSessionEndpoint(HttpMethod.GET, "/session/status"));
    }

    @Override
    public FacebookGroupRpaSessionStatus startLogin() {
        return toSessionStatus(invokeRpaSessionEndpoint(HttpMethod.POST, "/session/login/start"));
    }

    @Override
    public FacebookGroupRpaSessionStatus completeLogin() {
        return toSessionStatus(invokeRpaSessionEndpoint(HttpMethod.POST, "/session/login/complete"));
    }

    @Override
    public FacebookGroupRpaSessionStatus importSession(JsonNode storageState) {
        return toSessionStatus(invokeRpaSessionEndpoint(HttpMethod.POST, "/session/import", Map.of(
                "storageState", storageState
        )));
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

        JsonNode rootResult = response == null ? null : response.path("result");
        JsonNode details = rootResult == null ? null : rootResult.path("details");
        JsonNode nestedResult = details == null ? null : details.path("result");
        boolean success = response != null
                && (booleanValue(response, "success")
                || booleanValue(response, "ok")
                || booleanValue(rootResult, "success")
                || booleanValue(rootResult, "ok")
                || booleanValue(details, "success")
                || booleanValue(details, "ok")
                || booleanValue(nestedResult, "success")
                || booleanValue(nestedResult, "ok")
                || booleanValue(response.path("details"), "success")
                || booleanValue(response.path("details"), "ok"));
        String message = firstText(
                rootResult == null ? null : rootResult.path("message"),
                rootResult == null ? null : rootResult.path("error"),
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
                rootResult == null ? null : rootResult.path("postUrl"),
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

    private JsonNode invokeRpaSessionEndpoint(HttpMethod method, String path) {
        return invokeRpaSessionEndpoint(method, path, Map.of());
    }

    private JsonNode invokeRpaSessionEndpoint(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = StringUtils.hasText(properties.rpaAuthToken())
                ? properties.rpaAuthToken().trim()
                : authTokenResolver.resolveToken();
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token.trim());
        }

        String url = effectiveRpaBaseUrl() + path;
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    method,
                    new HttpEntity<>(body, headers),
                    JsonNode.class
            );
            return response.getBody();
        } catch (RestClientResponseException exception) {
            String responseBody = exception.getResponseBodyAsString();
            String details = StringUtils.hasText(responseBody) ? responseBody : "<empty response body>";
            throw new IllegalStateException(
                    "Facebook Group RPA session endpoint failed. status=" + exception.getRawStatusCode()
                            + ", url=" + url
                            + ", body=" + details,
                    exception
            );
        }
    }

    private FacebookGroupRpaSessionStatus toSessionStatus(JsonNode response) {
        return new FacebookGroupRpaSessionStatus(
                booleanValue(response, "success") || booleanValue(response, "ok"),
                booleanValue(response, "sessionExists"),
                booleanValue(response, "loginInProgress"),
                firstText(response == null ? null : response.path("storageStatePath")),
                effectiveRpaBaseUrl() + "/session/login/start",
                effectiveRpaBaseUrl() + "/session/login/complete",
                firstText(
                        response == null ? null : response.path("message"),
                        response == null ? null : response.path("error")
                )
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
            String details = StringUtils.hasText(body) ? body : "<empty response body>";
            throw new IllegalStateException(
                    "Facebook Group RPA tool failed. status=" + exception.getRawStatusCode()
                            + ", url=" + effectiveToolsInvokeUrl()
                            + ", body=" + details,
                    exception
            );
        }
    }

    private String effectiveToolsInvokeUrl() {
        String overrideUrl = properties.effectiveRpaToolsInvokeUrl();
        return StringUtils.hasText(overrideUrl) ? overrideUrl : openClawProperties.toolsInvokeUrl();
    }

    private String effectiveRpaBaseUrl() {
        String toolsInvokeUrl = effectiveToolsInvokeUrl();
        if (toolsInvokeUrl.endsWith("/tools/invoke")) {
            return toolsInvokeUrl.substring(0, toolsInvokeUrl.length() - "/tools/invoke".length());
        }
        int lastSlash = toolsInvokeUrl.lastIndexOf('/');
        return lastSlash > 8 ? toolsInvokeUrl.substring(0, lastSlash) : toolsInvokeUrl;
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
