package com.example.recruitmentbot.interview.repository;

import com.example.recruitmentbot.interview.domain.HrInterviewNotification;
import com.example.recruitmentbot.interview.domain.HrInterviewNotificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HrInterviewNotificationRepository extends JpaRepository<HrInterviewNotification, Long> {

    List<HrInterviewNotification> findAllByHrRecipientIdAndStatusOrderBySentAtDesc(
            String hrRecipientId,
            HrInterviewNotificationStatus status
    );

    Optional<HrInterviewNotification> findFirstByConversationIdAndStatusOrderBySentAtDesc(
            Long conversationId,
            HrInterviewNotificationStatus status
    );

    List<HrInterviewNotification> findAllByStatusAndResponseDeadlineAtBefore(
            HrInterviewNotificationStatus status,
            OffsetDateTime responseDeadlineAt
    );
}
