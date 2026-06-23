package com.example.recruitmentbot.facebookgroup.service;

public record FacebookGroupRpaRequest(
        Long jobDescriptionId,
        Long groupId,
        String groupName,
        String groupReference,
        String generatedContent,
        String scriptName
) {
}
