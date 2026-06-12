package com.example.recruitmentbot.jobposting.repository;

import com.example.recruitmentbot.jobposting.domain.FacebookJobPost;
import com.example.recruitmentbot.jobposting.domain.FacebookPostStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacebookJobPostRepository extends JpaRepository<FacebookJobPost, Long> {

    Optional<FacebookJobPost> findFirstByJobDescriptionIdAndStatusOrderByPostedAtDesc(
            Long jobDescriptionId,
            FacebookPostStatus status
    );
}
