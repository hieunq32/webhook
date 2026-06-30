package com.example.recruitmentbot.facebookgroup.service;

public record FacebookGroupRpaSessionStatus(
        boolean success,
        boolean sessionExists,
        boolean loginInProgress,
        String storageStatePath,
        String loginStartUrl,
        String loginCompleteUrl,
        String message
) {
}
