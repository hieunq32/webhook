package com.example.recruitmentbot.interview.service;

import java.time.OffsetDateTime;
import java.util.List;

public interface VOfficeAvailabilityService {

    List<VOfficeSlot> getAvailableSlots(String department, OffsetDateTime fromTime, OffsetDateTime toTime);

    record VOfficeSlot(
            String slotKey,
            String department,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
    }
}
