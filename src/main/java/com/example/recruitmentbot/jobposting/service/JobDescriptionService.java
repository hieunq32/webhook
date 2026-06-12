package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionResponse;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionUpsertRequest;
import com.example.recruitmentbot.jobposting.repository.FacebookJobPostRepository;
import com.example.recruitmentbot.jobposting.repository.JobDescriptionRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobDescriptionService {

    private final JobDescriptionRepository jobDescriptionRepository;
    private final FacebookJobPostRepository facebookJobPostRepository;
    private final JobDescriptionMapper jobDescriptionMapper;

    public JobDescriptionService(
            JobDescriptionRepository jobDescriptionRepository,
            FacebookJobPostRepository facebookJobPostRepository,
            JobDescriptionMapper jobDescriptionMapper
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.facebookJobPostRepository = facebookJobPostRepository;
        this.jobDescriptionMapper = jobDescriptionMapper;
    }

    @Transactional
    public JobDescriptionResponse create(JobDescriptionUpsertRequest request) {
        return toResponse(createEntity(request));
    }

    @Transactional
    public JobDescriptionResponse update(Long id, JobDescriptionUpsertRequest request) {
        return toResponse(updateEntity(id, request));
    }

    @Transactional(readOnly = true)
    public JobDescriptionResponse get(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<JobDescriptionResponse> list() {
        return jobDescriptionRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public JobDescription updateStatus(Long id, JobStatus status) {
        JobDescription jobDescription = getEntity(id);
        jobDescription.setStatus(status);
        return jobDescriptionRepository.save(jobDescription);
    }

    @Transactional
    public JobDescription createEntity(JobDescriptionUpsertRequest request) {
        JobDescription jobDescription = new JobDescription();
        apply(jobDescription, request);
        return jobDescriptionRepository.save(jobDescription);
    }

    @Transactional
    public JobDescription updateEntity(Long id, JobDescriptionUpsertRequest request) {
        JobDescription jobDescription = getEntity(id);
        apply(jobDescription, request);
        return jobDescriptionRepository.save(jobDescription);
    }

    @Transactional(readOnly = true)
    public JobDescription getEntity(Long id) {
        return jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job description not found: " + id));
    }

    private void apply(JobDescription jobDescription, JobDescriptionUpsertRequest request) {
        jobDescription.setTitle(request.title());
        jobDescription.setDescription(request.description());
        jobDescription.setRequirements(request.requirements());
        jobDescription.setSalary(request.salary());
        jobDescription.setLocation(request.location());
        jobDescription.setWorkType(request.workType());
        jobDescription.setStatus(request.status());
        jobDescription.setApplicantCount(request.applicantCount());
    }

    private JobDescriptionResponse toResponse(JobDescription jobDescription) {
        return jobDescriptionMapper.toResponse(
                jobDescription,
                facebookJobPostRepository.findFirstByJobDescriptionIdAndStatusOrderByPostedAtDesc(
                        jobDescription.getId(),
                        com.example.recruitmentbot.jobposting.domain.FacebookPostStatus.ACTIVE
                )
        );
    }
}
