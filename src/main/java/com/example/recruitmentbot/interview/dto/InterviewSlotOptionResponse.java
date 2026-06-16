package com.example.recruitmentbot.interview.dto;

public record InterviewSlotOptionResponse(
        Long slotId,
        int optionNumber,
        String label,
        String startTime,
        String endTime
) {
}
