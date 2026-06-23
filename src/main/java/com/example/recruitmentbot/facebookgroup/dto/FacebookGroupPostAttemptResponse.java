package com.example.recruitmentbot.facebookgroup.dto;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostStatus;

public record FacebookGroupPostAttemptResponse(
        Long historyId,
        Long groupId,
        String groupName,
        String groupReference,
        FacebookGroupPostStatus status,
        int retryCount,
        String errorReason
) {
}
