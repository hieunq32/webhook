package com.example.recruitmentbot.jobposting.service;

public record FacebookPublishResult(
        boolean success,
        String facebookPostId,
        String errorMessage
) {
}
