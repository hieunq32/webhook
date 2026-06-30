package com.example.recruitmentbot.integration.facebookgroup.dto;

public record ResolvedFacebookGroupTargetResponse(
        Long internalGroupId,
        String displayName,
        String groupReference
) {
}
