package com.example.recruitmentbot.openclaw.controller;

import com.example.recruitmentbot.openclaw.dto.OpenClawHrPromptRequest;
import com.example.recruitmentbot.openclaw.dto.OpenClawResponsesRequest;
import com.example.recruitmentbot.openclaw.dto.OpenClawToolInvokeRequest;
import com.example.recruitmentbot.openclaw.service.OpenClawGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/openclaw")
public class OpenClawIntegrationController {

    private final OpenClawGatewayClient openClawGatewayClient;

    public OpenClawIntegrationController(OpenClawGatewayClient openClawGatewayClient) {
        this.openClawGatewayClient = openClawGatewayClient;
    }

    @GetMapping("/health")
    public ResponseEntity<JsonNode> health() {
        return ResponseEntity.ok(openClawGatewayClient.checkHealth());
    }

    @GetMapping("/test")
    public ResponseEntity<JsonNode> test() {
        return ResponseEntity.ok(openClawGatewayClient.runConnectivityTest());
    }

    @PostMapping("/hr-test")
    public ResponseEntity<JsonNode> hrTest(@RequestBody OpenClawHrPromptRequest request) {
        return ResponseEntity.ok(openClawGatewayClient.runResponse(new OpenClawResponsesRequest(
                request.prompt(),
                request.instructions(),
                request.hrSenderId(),
                null,
                null,
                request.backendModel(),
                request.sessionKey()
        )));
    }

    @PostMapping("/responses")
    public ResponseEntity<JsonNode> responses(@RequestBody OpenClawResponsesRequest request) {
        return ResponseEntity.ok(openClawGatewayClient.runResponse(request));
    }

    @PostMapping("/tools/invoke")
    public ResponseEntity<JsonNode> invokeTool(@RequestBody OpenClawToolInvokeRequest request) {
        return ResponseEntity.ok(openClawGatewayClient.invokeTool(request));
    }
}
