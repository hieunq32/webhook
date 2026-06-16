package com.example.recruitmentbot.council.repository;

import com.example.recruitmentbot.council.domain.RecruitmentCouncil;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecruitmentCouncilRepository extends JpaRepository<RecruitmentCouncil, Long> {

    Optional<RecruitmentCouncil> findFirstByCodeIgnoreCase(String code);

    Optional<RecruitmentCouncil> findFirstByNameIgnoreCase(String name);

    Optional<RecruitmentCouncil> findFirstByRepresentativeSenderIdAndActiveTrue(String representativeSenderId);

    List<RecruitmentCouncil> findAllByIdInAndActiveTrue(Collection<Long> ids);
}
