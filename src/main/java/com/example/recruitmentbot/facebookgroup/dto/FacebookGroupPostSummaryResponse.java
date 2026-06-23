package com.example.recruitmentbot.facebookgroup.dto;

import java.util.List;

public record FacebookGroupPostSummaryResponse(
        Long jobDescriptionId,
        String jobTitle,
        boolean success,
        String message,
        int totalGroups,
        int successCount,
        int failedCount,
        int skippedCount,
        String generatedContent,
        List<FacebookGroupPostAttemptResponse> attempts
) {
}
