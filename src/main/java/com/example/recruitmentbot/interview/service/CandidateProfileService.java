package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.interview.config.InterviewSchedulingProperties;
import com.example.recruitmentbot.interview.domain.CandidateProfile;
import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartRequest;
import com.example.recruitmentbot.interview.repository.CandidateProfileRepository;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import java.util.List;
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
        profile.setJobDescriptionId(request.jobDescriptionId());
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

    public List<CandidateProfile> getAllPassedCandidates() {
        return candidateProfileRepository.findAllByPassCvTrueOrderByUpdatedAtAsc();
    }

    public List<CandidateProfile> getAllNotPassedCandidates() {
        return candidateProfileRepository.findAllByPassCvFalseOrderByUpdatedAtAsc();
    }

    public CandidateProfile ensureProfileExistsForMessengerSender(String senderId) {
        return candidateProfileRepository.findBySenderId(senderId)
                .orElseGet(() -> candidateProfileRepository.save(buildPlaceholderProfile(senderId)));
    }

    public CandidateProfile assignJobFromMessengerSender(String senderId, JobDescription jobDescription) {
        CandidateProfile profile = ensureProfileExistsForMessengerSender(senderId);
        profile.setAppliedPosition(jobDescription.getTitle());
        profile.setJobDescriptionId(jobDescription.getId());
        return candidateProfileRepository.save(profile);
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
