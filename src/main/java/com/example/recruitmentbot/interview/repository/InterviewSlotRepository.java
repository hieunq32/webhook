package com.example.recruitmentbot.interview.repository;

import com.example.recruitmentbot.interview.domain.InterviewSlot;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface InterviewSlotRepository extends JpaRepository<InterviewSlot, Long> {

    Optional<InterviewSlot> findBySlotKey(String slotKey);

    List<InterviewSlot> findAllByIdIn(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select slot
            from InterviewSlot slot
            where slot.department = :department
              and slot.startTime >= :fromTime and slot.startTime < :toTime
            order by slot.startTime asc
            """)
    List<InterviewSlot> findUpcomingForUpdateByDepartment(String department, OffsetDateTime fromTime, OffsetDateTime toTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select slot
            from InterviewSlot slot
            where slot.startTime >= :fromTime and slot.startTime < :toTime
            order by slot.startTime asc
            """)
    List<InterviewSlot> findUpcomingForUpdate(OffsetDateTime fromTime, OffsetDateTime toTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from InterviewSlot slot where slot.id = :slotId")
    Optional<InterviewSlot> findByIdForUpdate(Long slotId);

    List<InterviewSlot> findAllByLockedByConversationId(Long conversationId);

    List<InterviewSlot> findAllByStartTimeBetweenOrderByStartTimeAsc(OffsetDateTime fromTime, OffsetDateTime toTime);
}
