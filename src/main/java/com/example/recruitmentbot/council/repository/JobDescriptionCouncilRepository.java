package com.example.recruitmentbot.council.repository;

import com.example.recruitmentbot.council.domain.JobDescriptionCouncil;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDescriptionCouncilRepository extends JpaRepository<JobDescriptionCouncil, Long> {

    List<JobDescriptionCouncil> findAllByJobDescriptionId(Long jobDescriptionId);

    List<JobDescriptionCouncil> findAllByCouncilIdIn(Collection<Long> councilIds);

    void deleteAllByJobDescriptionId(Long jobDescriptionId);
}
