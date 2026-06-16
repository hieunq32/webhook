package com.example.recruitmentbot.interview.domain;

public enum InterviewConversationState {
    AWAITING_SLOT_SELECTION,
    HR_PENDING,
    AWAITING_RESCHEDULE_REASON,
    HR_RESCHEDULE_REVIEW,
    CONFIRMED,
    RESCHEDULE_REQUESTED,
    CANCELLED
}
