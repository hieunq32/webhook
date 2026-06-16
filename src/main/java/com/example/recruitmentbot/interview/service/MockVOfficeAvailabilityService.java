package com.example.recruitmentbot.interview.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MockVOfficeAvailabilityService implements VOfficeAvailabilityService {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Saigon");
    private static final List<LocalTime[]> SLOT_TEMPLATES = List.of(
            new LocalTime[] {LocalTime.of(9, 0), LocalTime.of(10, 0)},
            new LocalTime[] {LocalTime.of(10, 30), LocalTime.of(11, 30)},
            new LocalTime[] {LocalTime.of(14, 0), LocalTime.of(15, 0)},
            new LocalTime[] {LocalTime.of(15, 30), LocalTime.of(16, 30)}
    );

    @Override
    public List<VOfficeSlot> getAvailableSlots(String department, OffsetDateTime fromTime, OffsetDateTime toTime) {
        LocalDate startDate = fromTime.toLocalDate();
        LocalDate endDate = toTime.toLocalDate();
        List<VOfficeSlot> slots = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (LocalTime[] template : SLOT_TEMPLATES) {
                OffsetDateTime startTime = date.atTime(template[0]).atZone(ZONE_ID).toOffsetDateTime();
                OffsetDateTime endTime = date.atTime(template[1]).atZone(ZONE_ID).toOffsetDateTime();
                if (startTime.isBefore(fromTime) || startTime.isAfter(toTime)) {
                    continue;
                }
                String slotKey = department + "|" + startTime + "|" + endTime;
                slots.add(new VOfficeSlot(slotKey, department, startTime, endTime));
            }
        }
        return slots;
    }
}
