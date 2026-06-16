package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.interview.domain.InterviewSlot;
import java.util.List;

public interface InterviewSlotSelectionResolver {

    Resolution resolve(String candidateMessage, List<InterviewSlot> offeredSlots);

    record Resolution(ResolutionType type, Integer optionNumber) {
    }

    enum ResolutionType {
        SELECTED,
        RESCHEDULE,
        UNSURE,
        AMBIGUOUS,
        NO_MATCH
    }
}
