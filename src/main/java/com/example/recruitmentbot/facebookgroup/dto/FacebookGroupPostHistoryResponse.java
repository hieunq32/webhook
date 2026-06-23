package com.example.recruitmentbot.facebookgroup.dto;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostStatus;
import java.time.OffsetDateTime;

public record FacebookGroupPostHistoryResponse(
        Long id,
        Long jobDescriptionId,
        String jobTitle,
        Long groupId,
        String groupName,
        String groupReference,
        OffsetDateTime postedAt,
        FacebookGroupPostStatus status,
        Integer retryCount,
        String errorReason
) {
}
