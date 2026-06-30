package com.example.recruitmentbot.integration.facebookgroup.dto;

public record ExternalFacebookGroupTargetRequest(
        String groupId,
        String facebookGroupId,
        String facebookGroupUrl,
        String groupName
) {
}
