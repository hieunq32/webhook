package com.example.recruitmentbot.openclaw.dto;

public record OpenClawResponsesRequest(
        String input,
        String instructions,
        String user,
        String model,
        String agentId,
        String backendModel,
        String sessionKey
) {
}
