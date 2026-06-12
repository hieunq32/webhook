package com.example.recruitmentbot.jobposting.dto;

import com.example.recruitmentbot.jobposting.domain.FacebookPostStatus;
import java.time.OffsetDateTime;

public record FacebookPostResponse(
        Long id,
        String facebookPostId,
        String generatedContent,
        FacebookPostStatus status,
        OffsetDateTime postedAt,
        OffsetDateTime deletedAt
) {
}
