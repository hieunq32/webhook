package com.example.recruitmentbot.interview.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "interview")
public record InterviewSchedulingProperties(
        boolean enabled,
        @NotBlank String hrRecipientId,
        @NotBlank String defaultDepartment,
        @NotBlank String interviewLocation,
        @NotBlank String preparationNotes,
        @Min(1) int slotOfferCount,
        @Min(1) int softLockMinutes,
        @Min(1) int hrResponseTimeoutMinutes,
        @NotBlank String slotSelectionPromptTemplate
) {
}
