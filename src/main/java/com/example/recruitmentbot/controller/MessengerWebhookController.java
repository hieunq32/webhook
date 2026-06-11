package com.example.recruitmentbot.controller;

import com.example.recruitmentbot.service.MessengerWebhookService;
import com.example.recruitmentbot.service.WebhookVerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
public class MessengerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MessengerWebhookController.class);

    private final WebhookVerificationService webhookVerificationService;
    private final MessengerWebhookService messengerWebhookService;

    public MessengerWebhookController(
            WebhookVerificationService webhookVerificationService,
            MessengerWebhookService messengerWebhookService
    ) {
        this.webhookVerificationService = webhookVerificationService;
        this.messengerWebhookService = messengerWebhookService;
    }

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        return webhookVerificationService.verifyWebhook(mode, verifyToken, challenge);
    }

    @PostMapping
    public ResponseEntity<String> receiveWebhook(@RequestBody JsonNode payload) {
        log.info("Incoming Facebook webhook payload:\n{}", payload.toPrettyString());
        messengerWebhookService.processIncomingWebhook(payload);
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
