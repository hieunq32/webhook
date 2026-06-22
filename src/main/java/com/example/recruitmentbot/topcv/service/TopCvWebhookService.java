package com.example.recruitmentbot.topcv.service;

import com.example.recruitmentbot.topcv.config.TopCvWebhookProperties;
import com.example.recruitmentbot.topcv.dto.TopCvWebhookAckResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class TopCvWebhookService {

    private static final Logger log = LoggerFactory.getLogger(TopCvWebhookService.class);

    private static final Set<String> ACTION_FIELDS = Set.of("action", "event", "eventType", "event_type", "type", "status");
    private static final Set<String> JOB_FIELDS = Set.of("jobId", "job_id", "jobCode", "job_code", "postingId", "posting_id");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "candidateId", "candidate_id", "cvId", "cv_id", "profileId", "profile_id", "applicationId", "application_id"
    );

    private final ObjectMapper objectMapper;
    private final TopCvWebhookProperties properties;

    public TopCvWebhookService(ObjectMapper objectMapper, TopCvWebhookProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public TopCvWebhookAckResponse handleWebhook(String rawPayload, HttpHeaders headers, HttpServletRequest request) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "TopCV webhook is disabled");
        }
        if (!StringUtils.hasText(rawPayload)) {
            throw new ResponseStatusException(BAD_REQUEST, "TopCV webhook payload is empty");
        }

        validateApiKey(headers, request);

        JsonNode payload = parsePayload(rawPayload);
        String action = firstNonBlank(
                findTextValue(payload, ACTION_FIELDS),
                "unknown"
        );
        String jobReference = firstNonBlank(findTextValue(payload, JOB_FIELDS), "n/a");
        String candidateReference = firstNonBlank(findTextValue(payload, CANDIDATE_FIELDS), "n/a");

        log.info("Incoming TopCV webhook payload:\n{}", payload.toPrettyString());
        log.info(
                "Received TopCV webhook event. action={}, jobRef={}, candidateRef={}, remoteAddress={}",
                action,
                jobReference,
                candidateReference,
                request.getRemoteAddr()
        );

        return new TopCvWebhookAckResponse(
                true,
                "topcv",
                action,
                "TopCV webhook received"
        );
    }

    private void validateApiKey(HttpHeaders headers, HttpServletRequest request) {
        if (!properties.requireApiKey()) {
            return;
        }
        String expectedApiKey = trimToNull(properties.apiKey());
        if (!StringUtils.hasText(expectedApiKey)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "TopCV webhook API key is required but not configured");
        }

        String providedApiKey = firstNonBlank(
                trimToNull(headers.getFirst(firstNonBlank(trimToNull(properties.apiKeyHeaderName()), "X-TopCV-Api-Key"))),
                trimToNull(headers.getFirst("X-API-Key")),
                extractBearerToken(headers.getFirst(HttpHeaders.AUTHORIZATION)),
                trimToNull(request.getParameter(firstNonBlank(trimToNull(properties.authQueryParamName()), "apiKey")))
        );

        if (!expectedApiKey.equals(providedApiKey)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid TopCV webhook API key");
        }
    }

    private JsonNode parsePayload(String rawPayload) {
        try {
            return objectMapper.readTree(rawPayload);
        } catch (IOException ex) {
            log.warn("TopCV webhook payload is not valid JSON: {}", ex.getMessage());
            throw new ResponseStatusException(BAD_REQUEST, "TopCV webhook payload must be valid JSON");
        }
    }

    private String findTextValue(JsonNode node, Set<String> candidateFields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (String fieldName : candidateFields) {
                JsonNode fieldNode = node.get(fieldName);
                if (fieldNode != null) {
                    String textValue = asText(fieldNode);
                    if (StringUtils.hasText(textValue)) {
                        return textValue;
                    }
                }
            }
            Iterator<JsonNode> elements = node.elements();
            while (elements.hasNext()) {
                String nestedValue = findTextValue(elements.next(), candidateFields);
                if (StringUtils.hasText(nestedValue)) {
                    return nestedValue;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String nestedValue = findTextValue(item, candidateFields);
                if (StringUtils.hasText(nestedValue)) {
                    return nestedValue;
                }
            }
        }
        return null;
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isValueNode()) {
            return trimToNull(node.asText());
        }
        return trimToNull(node.toString());
    }

    private String extractBearerToken(String authorizationHeader) {
        String value = trimToNull(authorizationHeader);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String lowerCaseValue = value.toLowerCase(Locale.ROOT);
        if (lowerCaseValue.startsWith("bearer ")) {
            return trimToNull(value.substring(7));
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
