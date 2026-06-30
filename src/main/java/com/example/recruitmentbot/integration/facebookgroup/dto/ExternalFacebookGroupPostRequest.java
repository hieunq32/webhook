package com.example.recruitmentbot.integration.facebookgroup.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ExternalFacebookGroupPostRequest(
        String externalRequestId,
        @Valid @NotNull ExternalJobDescriptionRequest jobDescription,
        @Valid @NotEmpty List<ExternalFacebookGroupTargetRequest> targetGroups
) {
}
