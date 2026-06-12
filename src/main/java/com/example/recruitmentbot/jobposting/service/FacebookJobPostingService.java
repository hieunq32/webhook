package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.config.FacebookJobPostingProperties;
import com.example.recruitmentbot.jobposting.domain.FacebookJobPost;
import com.example.recruitmentbot.jobposting.domain.FacebookPostStatus;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionUpsertRequest;
import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.repository.FacebookJobPostRepository;
import com.example.recruitmentbot.jobposting.repository.JobDescriptionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FacebookJobPostingService {

    private static final Logger log = LoggerFactory.getLogger(FacebookJobPostingService.class);

    private final JobDescriptionService jobDescriptionService;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final FacebookJobPostRepository facebookJobPostRepository;
    private final FacebookPageClient facebookPageClient;
    private final JobPostContentGenerator jobPostContentGenerator;
    private final FacebookJobPostingProperties properties;

    public FacebookJobPostingService(
            JobDescriptionService jobDescriptionService,
            JobDescriptionRepository jobDescriptionRepository,
            FacebookJobPostRepository facebookJobPostRepository,
            FacebookPageClient facebookPageClient,
            JobPostContentGenerator jobPostContentGenerator,
            FacebookJobPostingProperties properties
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.facebookJobPostRepository = facebookJobPostRepository;
        this.facebookPageClient = facebookPageClient;
        this.jobPostContentGenerator = jobPostContentGenerator;
        this.properties = properties;
    }

    @Transactional
    public FacebookPostOperationResponse createAndPublish(JobDescriptionUpsertRequest request) {
        JobDescription saved = jobDescriptionService.createEntity(request);
        return publish(saved.getId());
    }

    @Transactional
    public FacebookPostOperationResponse updateAndRepublish(Long jobDescriptionId, JobDescriptionUpsertRequest request) {
        JobDescription updated = jobDescriptionService.updateEntity(jobDescriptionId, request);
        handleJobStatusChange(updated.getId(), updated.getStatus());
        if (updated.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(
                    updated.getId(),
                    false,
                    "update-and-republish",
                    "Only OPEN jobs can be published to Facebook",
                    null,
                    null
            );
        }
        return findActivePost(jobDescriptionId).isPresent() ? repost(jobDescriptionId) : publish(jobDescriptionId);
    }

    @Transactional
    public FacebookPostOperationResponse publish(Long jobDescriptionId) {
        JobDescription jobDescription = jobDescriptionService.getEntity(jobDescriptionId);
        if (!properties.enabled()) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish", "Facebook job posting is disabled", null, null);
        }
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish",
                    "Only OPEN jobs can be posted to Facebook", null, null);
        }

        Optional<FacebookJobPost> existing = findActivePost(jobDescriptionId);
        if (existing.isPresent()) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish",
                    "Active Facebook post already exists. Use repost endpoint instead.",
                    existing.get().getFacebookPostId(),
                    existing.get().getGeneratedContent());
        }

        return doPublish(jobDescription, "publish");
    }

    @Transactional
    public FacebookPostOperationResponse repost(Long jobDescriptionId) {
        JobDescription jobDescription = jobDescriptionService.getEntity(jobDescriptionId);
        if (!properties.enabled()) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "repost", "Facebook job posting is disabled", null, null);
        }
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "repost",
                    "Only OPEN jobs can be re-posted to Facebook", null, null);
        }

        Optional<FacebookJobPost> existing = findActivePost(jobDescriptionId);
        if (existing.isPresent()) {
            boolean deleted = facebookPageClient.deletePost(existing.get().getFacebookPostId());
            if (!deleted) {
                return new FacebookPostOperationResponse(jobDescriptionId, false, "repost",
                        "Failed to delete old Facebook post before re-posting",
                        existing.get().getFacebookPostId(),
                        existing.get().getGeneratedContent());
            }
            existing.get().markDeleted();
            facebookJobPostRepository.save(existing.get());
        }

        return doPublish(jobDescription, "repost");
    }

    @Transactional
    public FacebookPostOperationResponse delete(Long jobDescriptionId) {
        Optional<FacebookJobPost> activePost = findActivePost(jobDescriptionId);
        if (activePost.isEmpty()) {
            return new FacebookPostOperationResponse(jobDescriptionId, true, "delete",
                    "No active Facebook post found for this job", null, null);
        }

        boolean deleted = facebookPageClient.deletePost(activePost.get().getFacebookPostId());
        if (!deleted) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "delete",
                    "Failed to delete Facebook post",
                    activePost.get().getFacebookPostId(),
                    activePost.get().getGeneratedContent());
        }

        activePost.get().markDeleted();
        facebookJobPostRepository.save(activePost.get());
        return new FacebookPostOperationResponse(jobDescriptionId, true, "delete",
                "Facebook post deleted successfully",
                activePost.get().getFacebookPostId(),
                activePost.get().getGeneratedContent());
    }

    @Transactional
    public void handleJobStatusChange(Long jobDescriptionId, JobStatus status) {
        if (status == JobStatus.CLOSED || status == JobStatus.FILLED) {
            FacebookPostOperationResponse response = delete(jobDescriptionId);
            if (!response.success()) {
                log.warn("Failed to auto-delete Facebook post for closed job. jobId={}, message={}",
                        jobDescriptionId, response.message());
            }
        }
    }

    @Transactional
    public int repostStaleOpenJobs() {
        if (!properties.enabled()) {
            return 0;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(7);
        List<JobDescription> staleJobs = jobDescriptionRepository.findAllByStatusAndApplicantCountAndCreatedAtBefore(
                JobStatus.OPEN,
                0,
                cutoff
        );

        int successCount = 0;
        for (JobDescription staleJob : staleJobs) {
            FacebookPostOperationResponse response = repost(staleJob.getId());
            if (response.success()) {
                successCount++;
            } else {
                log.warn("Scheduled repost failed for jobId={}. message={}", staleJob.getId(), response.message());
            }
        }
        return successCount;
    }

    private FacebookPostOperationResponse doPublish(JobDescription jobDescription, String action) {
        final String generatedContent;
        try {
            generatedContent = jobPostContentGenerator.generatePost(jobDescription);
        } catch (Exception ex) {
            log.error("Failed to generate Facebook job post content via AI. jobId={}", jobDescription.getId(), ex);
            return new FacebookPostOperationResponse(
                    jobDescription.getId(),
                    false,
                    action,
                    "Failed to generate job post content via AI: " + ex.getMessage(),
                    null,
                    null
            );
        }

        FacebookPublishResult result = facebookPageClient.publishPost(generatedContent);
        if (!result.success()) {
            return new FacebookPostOperationResponse(
                    jobDescription.getId(),
                    false,
                    action,
                    "Failed to publish job to Facebook: " + result.errorMessage(),
                    null,
                    generatedContent
            );
        }

        FacebookJobPost post = new FacebookJobPost();
        post.setJobDescription(jobDescription);
        post.setFacebookPostId(result.facebookPostId());
        post.setGeneratedContent(generatedContent);
        post.setStatus(FacebookPostStatus.ACTIVE);
        facebookJobPostRepository.save(post);

        return new FacebookPostOperationResponse(
                jobDescription.getId(),
                true,
                action,
                "Facebook post published successfully",
                result.facebookPostId(),
                generatedContent
        );
    }

    private Optional<FacebookJobPost> findActivePost(Long jobDescriptionId) {
        return facebookJobPostRepository.findFirstByJobDescriptionIdAndStatusOrderByPostedAtDesc(
                jobDescriptionId,
                FacebookPostStatus.ACTIVE
        );
    }
}
