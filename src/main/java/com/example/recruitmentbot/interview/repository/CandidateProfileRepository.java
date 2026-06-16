package com.example.recruitmentbot.interview.repository;

import com.example.recruitmentbot.interview.domain.CandidateProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {

    Optional<CandidateProfile> findBySenderId(String senderId);

    Optional<CandidateProfile> findBySenderIdAndPassCvTrue(String senderId);

    List<CandidateProfile> findAllByPassCvTrueOrderByUpdatedAtAsc();

    List<CandidateProfile> findAllByPassCvFalseOrderByUpdatedAtAsc();
}
