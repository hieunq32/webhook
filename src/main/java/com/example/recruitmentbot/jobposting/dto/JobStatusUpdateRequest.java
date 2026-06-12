package com.example.recruitmentbot.jobposting.dto;

import com.example.recruitmentbot.jobposting.domain.JobStatus;
import jakarta.validation.constraints.NotNull;

public record JobStatusUpdateRequest(@NotNull JobStatus status) {
}
