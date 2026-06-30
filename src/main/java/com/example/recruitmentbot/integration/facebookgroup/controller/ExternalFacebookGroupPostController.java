package com.example.recruitmentbot.integration.facebookgroup.controller;

import com.example.recruitmentbot.config.IntegrationApiProperties;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupRpaSessionStatus;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupPostRequest;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupPostResponse;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupSessionImportRequest;
import com.example.recruitmentbot.integration.facebookgroup.service.ExternalFacebookGroupPostService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/integrations/facebook-groups")
public class ExternalFacebookGroupPostController {

    private final ExternalFacebookGroupPostService externalFacebookGroupPostService;
    private final IntegrationApiProperties integrationApiProperties;

    public ExternalFacebookGroupPostController(
            ExternalFacebookGroupPostService externalFacebookGroupPostService,
            IntegrationApiProperties integrationApiProperties
    ) {
        this.externalFacebookGroupPostService = externalFacebookGroupPostService;
        this.integrationApiProperties = integrationApiProperties;
    }

    @PostMapping("/job-posts")
    public ResponseEntity<ExternalFacebookGroupPostResponse> createJobAndPostToGroups(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody ExternalFacebookGroupPostRequest request
    ) {
        validateIntegrationAccess(apiKey);
        return ResponseEntity.ok(externalFacebookGroupPostService.createJobAndPostToGroups(request));
    }

    @GetMapping("/session/status")
    public ResponseEntity<FacebookGroupRpaSessionStatus> getSessionStatus(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey
    ) {
        validateIntegrationAccess(apiKey);
        return ResponseEntity.ok(externalFacebookGroupPostService.getSessionStatus());
    }

    @PostMapping("/session/login/start")
    public ResponseEntity<FacebookGroupRpaSessionStatus> startLogin(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey
    ) {
        validateIntegrationAccess(apiKey);
        return ResponseEntity.ok(externalFacebookGroupPostService.startLogin());
    }

    @PostMapping("/session/login/complete")
    public ResponseEntity<FacebookGroupRpaSessionStatus> completeLogin(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey
    ) {
        validateIntegrationAccess(apiKey);
        return ResponseEntity.ok(externalFacebookGroupPostService.completeLogin());
    }

    @PostMapping("/session/import")
    public ResponseEntity<FacebookGroupRpaSessionStatus> importSession(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody ExternalFacebookGroupSessionImportRequest request
    ) {
        validateIntegrationAccess(apiKey);
        return ResponseEntity.ok(externalFacebookGroupPostService.importSession(request));
    }

    private void validateIntegrationAccess(String apiKey) {
        if (!integrationApiProperties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "External integration API is disabled");
        }
        if (integrationApiProperties.requiresApiKey()
                && (!StringUtils.hasText(apiKey) || !integrationApiProperties.apiKey().equals(apiKey))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid integration API key");
        }
    }
}
