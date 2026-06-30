package com.example.recruitmentbot.integration.facebookgroup.dto;

import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import java.util.List;

public record ExternalFacebookGroupPostResponse(
        String externalRequestId,
        Long jobDescriptionId,
        String jobTitle,
        List<ResolvedFacebookGroupTargetResponse> targetGroups,
        FacebookGroupPostSummaryResponse postingSummary
) {
}
