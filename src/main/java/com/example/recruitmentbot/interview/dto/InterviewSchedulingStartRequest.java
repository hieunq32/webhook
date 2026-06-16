package com.example.recruitmentbot.interview.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record InterviewSchedulingStartRequest(
        @NotBlank String candidateSenderId,
        @NotBlank String candidateName,
        @NotBlank String appliedPosition,
        @NotNull @DecimalMin("0.0") BigDecimal cvScore,
        boolean passCv,
        String department,
        String assignedHrSenderId,
        Long jobDescriptionId
) {
}
