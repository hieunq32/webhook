package com.example.recruitmentbot.topcv.dto;

public record TopCvWebhookAckResponse(
        boolean success,
        String source,
        String action,
        String message
) {
}
