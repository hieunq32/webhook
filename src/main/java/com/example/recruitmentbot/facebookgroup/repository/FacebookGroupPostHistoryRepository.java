package com.example.recruitmentbot.facebookgroup.repository;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostHistory;
import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacebookGroupPostHistoryRepository extends JpaRepository<FacebookGroupPostHistory, Long> {

    long countByTargetGroupIdAndStatusAndPostedAtBetween(
            Long targetGroupId,
            FacebookGroupPostStatus status,
            OffsetDateTime from,
            OffsetDateTime to
    );

    List<FacebookGroupPostHistory> findTop50ByOrderByPostedAtDesc();
}
