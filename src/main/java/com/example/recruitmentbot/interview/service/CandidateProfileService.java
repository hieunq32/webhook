package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.interview.config.InterviewSchedulingProperties;
import com.example.recruitmentbot.interview.domain.CandidateProfile;
import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartRequest;
import com.example.recruitmentbot.interview.repository.CandidateProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CandidateProfileService {

    private final CandidateProfileRepository candidateProfileRepository;
    private final InterviewSchedulingProperties properties;

    public CandidateProfileService(
            CandidateProfileRepository candidateProfileRepository,
            InterviewSchedulingProperties properties
    ) {
        this.candidateProfileRepository = candidateProfileRepository;
        this.properties = properties;
    }

    public CandidateProfile upsertFromInterviewStartRequest(InterviewSchedulingStartRequest request) {
        CandidateProfile profile = candidateProfileRepository.findBySenderId(request.candidateSenderId())
                .orElseGet(CandidateProfile::new);
        profile.setSenderId(request.candidateSenderId());
        profile.setCandidateName(request.candidateName());
        profile.setAppliedPosition(request.appliedPosition());
        profile.setDepartment(StringUtils.hasText(request.department()) ? request.department().trim() : properties.defaultDepartment());
        profile.setCvScore(request.cvScore());
        profile.setPassCv(request.passCv());
        profile.setAssignedHrSenderId(
                StringUtils.hasText(request.assignedHrSenderId())
                        ? request.assignedHrSenderId().trim()
                        : properties.hrRecipientId()
        );
        return candidateProfileRepository.save(profile);
    }

    public CandidateProfile getPassedCandidateBySenderId(String senderId) {
        return candidateProfileRepository.findBySenderIdAndPassCvTrue(senderId).orElse(null);
    }

    public CandidateProfile ensureProfileExistsForMessengerSender(String senderId) {
        return candidateProfileRepository.findBySenderId(senderId)
                .orElseGet(() -> candidateProfileRepository.save(buildPlaceholderProfile(senderId)));
    }

    private CandidateProfile buildPlaceholderProfile(String senderId) {
        CandidateProfile profile = new CandidateProfile();
        profile.setSenderId(senderId);
        profile.setCandidateName("Pending Candidate " + senderId);
        profile.setAppliedPosition("TBD");
        profile.setDepartment(properties.defaultDepartment());
        profile.setCvScore(java.math.BigDecimal.ZERO);
        profile.setPassCv(false);
        profile.setAssignedHrSenderId(properties.hrRecipientId());
        return profile;
    }
}
