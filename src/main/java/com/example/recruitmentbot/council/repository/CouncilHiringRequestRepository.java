package com.example.recruitmentbot.council.repository;

import com.example.recruitmentbot.council.domain.CouncilHiringRequest;
import com.example.recruitmentbot.council.domain.CouncilHiringRequestStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouncilHiringRequestRepository extends JpaRepository<CouncilHiringRequest, Long> {

    List<CouncilHiringRequest> findAllByStatusOrderByCreatedAtDesc(CouncilHiringRequestStatus status);
}
