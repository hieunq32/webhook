package com.example.recruitmentbot.jobposting.dto;

public record FacebookPostOperationResponse(
        Long jobDescriptionId,
        boolean success,
        String action,
        String message,
        String facebookPostId,
        String generatedContent
) {
}
