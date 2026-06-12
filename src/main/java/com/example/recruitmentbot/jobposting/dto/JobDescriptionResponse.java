package com.example.recruitmentbot.jobposting.dto;

import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.domain.WorkType;
import java.time.OffsetDateTime;

public record JobDescriptionResponse(
        Long id,
        String title,
        String description,
        String requirements,
        String salary,
        String location,
        WorkType workType,
        JobStatus status,
        Integer applicantCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        FacebookPostResponse activeFacebookPost
) {
}
