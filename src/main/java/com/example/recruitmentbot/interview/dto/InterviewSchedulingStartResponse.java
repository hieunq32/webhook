package com.example.recruitmentbot.interview.dto;

import java.util.List;

public record InterviewSchedulingStartResponse(
        Long conversationId,
        String state,
        String candidateSenderId,
        String candidateName,
        List<InterviewSlotOptionResponse> offeredSlots
) {
}
