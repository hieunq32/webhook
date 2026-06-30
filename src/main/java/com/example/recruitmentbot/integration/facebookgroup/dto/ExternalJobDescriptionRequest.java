package com.example.recruitmentbot.integration.facebookgroup.dto;

import jakarta.validation.constraints.NotBlank;

public record ExternalJobDescriptionRequest(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String requirements,
        @NotBlank String salary,
        @NotBlank String location,
        String workType,
        String status
) {
}
