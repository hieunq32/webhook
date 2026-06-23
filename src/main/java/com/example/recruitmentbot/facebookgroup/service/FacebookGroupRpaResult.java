package com.example.recruitmentbot.facebookgroup.service;

public record FacebookGroupRpaResult(
        boolean success,
        String message,
        String postUrl,
        String rawResponse
) {
}
