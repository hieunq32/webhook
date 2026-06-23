package com.example.recruitmentbot.facebookgroup.dto;

import java.time.OffsetDateTime;

public record FacebookGroupTargetResponse(
        Long id,
        String displayName,
        String groupReference,
        Boolean active,
        Integer priorityOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
