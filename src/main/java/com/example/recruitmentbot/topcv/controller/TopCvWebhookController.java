package com.example.recruitmentbot.topcv.controller;

import com.example.recruitmentbot.topcv.dto.TopCvWebhookAckResponse;
import com.example.recruitmentbot.topcv.service.TopCvWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/topcv")
public class TopCvWebhookController {

    private final TopCvWebhookService topCvWebhookService;

    public TopCvWebhookController(TopCvWebhookService topCvWebhookService) {
        this.topCvWebhookService = topCvWebhookService;
    }

    @PostMapping("/cv")
    public ResponseEntity<TopCvWebhookAckResponse> receiveWebhook(
            @RequestBody(required = false) String rawPayload,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(topCvWebhookService.handleWebhook(rawPayload, headers, request));
    }
}
