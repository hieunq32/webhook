package com.example.recruitmentbot.openclaw.dto;

public record OpenClawHrPromptRequest(
        String prompt,
        String instructions,
        String hrSenderId,
        String backendModel,
        String sessionKey
) {
}
