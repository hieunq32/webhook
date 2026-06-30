package com.example.recruitmentbot.integration.facebookgroup.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record ExternalFacebookGroupSessionImportRequest(
        String sessionOwnerKey,
        @NotNull JsonNode storageState
) {
}
