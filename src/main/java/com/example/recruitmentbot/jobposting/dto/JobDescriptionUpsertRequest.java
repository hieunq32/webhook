package com.example.recruitmentbot.jobposting.dto;

import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.domain.WorkType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record JobDescriptionUpsertRequest(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String requirements,
        @NotBlank String salary,
        @NotBlank String location,
        @NotNull WorkType workType,
        @NotNull JobStatus status,
        @NotNull @Min(0) Integer applicantCount
) {
}
