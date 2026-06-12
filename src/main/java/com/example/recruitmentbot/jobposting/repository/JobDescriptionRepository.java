package com.example.recruitmentbot.jobposting.repository;

import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    List<JobDescription> findAllByStatusAndApplicantCountAndCreatedAtBefore(
            JobStatus status,
            Integer applicantCount,
            OffsetDateTime createdAt
    );
}
