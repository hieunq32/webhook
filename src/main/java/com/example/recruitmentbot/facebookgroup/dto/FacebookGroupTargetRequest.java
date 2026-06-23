package com.example.recruitmentbot.facebookgroup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FacebookGroupTargetRequest(
        @NotBlank String displayName,
        @NotBlank String groupReference,
        @NotNull Boolean active,
        @NotNull Integer priorityOrder
) {
}
