package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.council.service.CouncilAssignmentResult;
import com.example.recruitmentbot.council.service.RecruitmentCouncilService;
import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import com.example.recruitmentbot.jobposting.config.FacebookJobPostingProperties;
import com.example.recruitmentbot.jobposting.domain.FacebookJobPost;
import com.example.recruitmentbot.jobposting.domain.FacebookPostStatus;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.WorkType;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionUpsertRequest;
import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.repository.FacebookJobPostRepository;
import com.example.recruitmentbot.jobposting.repository.JobDescriptionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FacebookJobPostingService {

    private static final Logger log = LoggerFactory.getLogger(FacebookJobPostingService.class);

    private final JobDescriptionService jobDescriptionService;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final FacebookJobPostRepository facebookJobPostRepository;
    private final FacebookPageClient facebookPageClient;
    private final JobPostContentGenerator jobPostContentGenerator;
    private final FacebookJobPostingProperties properties;
    private final RecruitmentCouncilService recruitmentCouncilService;

    public FacebookJobPostingService(
            JobDescriptionService jobDescriptionService,
            JobDescriptionRepository jobDescriptionRepository,
            FacebookJobPostRepository facebookJobPostRepository,
            FacebookPageClient facebookPageClient,
            JobPostContentGenerator jobPostContentGenerator,
            FacebookJobPostingProperties properties,
            RecruitmentCouncilService recruitmentCouncilService
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.facebookJobPostRepository = facebookJobPostRepository;
        this.facebookPageClient = facebookPageClient;
        this.jobPostContentGenerator = jobPostContentGenerator;
        this.properties = properties;
        this.recruitmentCouncilService = recruitmentCouncilService;
    }

    @Transactional
    public FacebookPostOperationResponse createAndPublish(JobDescriptionUpsertRequest request) {
        JobDescription saved = jobDescriptionService.createEntity(request);
        return publish(saved.getId());
    }

    @Transactional
    public FacebookPostOperationResponse createAndPublishFromText(String rawMessage) {
        return createAndPublishFromText(rawMessage, null);
    }

    @Transactional
    public FacebookPostOperationResponse createAndPublishFromText(String rawMessage, PageAdminAccount actor) {
        CouncilAssignmentResult assignmentInput = new CouncilAssignmentResult(rawMessage, null);
        JobDescriptionUpsertRequest request = buildRequestFromRawText(assignmentInput.sanitizedJobContent(), JobStatus.OPEN, 0);
        JobDescription saved = jobDescriptionService.createEntity(request);
        CouncilAssignmentResult assignment = recruitmentCouncilService.assignCouncilsFromRawMessage(saved.getId(), rawMessage, actor);
        if (!assignment.sanitizedJobContent().equals(rawMessage)) {
            JobDescriptionUpsertRequest sanitizedRequest = buildRequestFromRawText(assignment.sanitizedJobContent(), JobStatus.OPEN, 0);
            saved = jobDescriptionService.updateEntity(saved.getId(), sanitizedRequest);
        }
        return withCouncilSummary(publish(saved.getId()), assignment.councilSummary());
    }

    @Transactional
    public FacebookPostOperationResponse updateFromTextAndRepublish(Long jobDescriptionId, String rawMessage) {
        return updateFromTextAndRepublish(jobDescriptionId, rawMessage, null);
    }

    @Transactional
    public FacebookPostOperationResponse updateFromTextAndRepublish(Long jobDescriptionId, String rawMessage, PageAdminAccount actor) {
        JobDescription existing = jobDescriptionService.getEntity(jobDescriptionId);
        CouncilAssignmentResult assignment = recruitmentCouncilService.assignCouncilsFromRawMessage(jobDescriptionId, rawMessage, actor);
        JobDescriptionUpsertRequest request = buildRequestFromRawText(
                assignment.sanitizedJobContent(),
                existing.getStatus(),
                existing.getApplicantCount()
        );
        JobDescription updated = jobDescriptionService.updateEntity(jobDescriptionId, request);
        if (updated.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(
                    updated.getId(),
                    false,
                    "update-and-republish",
                    "Only OPEN jobs can be re-posted to Facebook",
                    null,
                    null,
                    assignment.councilSummary()
            );
        }
        FacebookPostOperationResponse response = findActivePost(jobDescriptionId).isPresent() ? repost(jobDescriptionId) : publish(jobDescriptionId);
        return withCouncilSummary(response, assignment.councilSummary());
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
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish", "Facebook job posting is disabled", null, null, null);
        }
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish",
                    "Only OPEN jobs can be posted to Facebook", null, null, null);
        }

        Optional<FacebookJobPost> existing = findActivePost(jobDescriptionId);
        if (existing.isPresent()) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "publish",
                    "Active Facebook post already exists. Use repost endpoint instead.",
                    existing.get().getFacebookPostId(),
                    existing.get().getGeneratedContent(),
                    null);
        }

        return doPublish(jobDescription, "publish");
    }

    @Transactional
    public FacebookPostOperationResponse repost(Long jobDescriptionId) {
        JobDescription jobDescription = jobDescriptionService.getEntity(jobDescriptionId);
        if (!properties.enabled()) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "repost", "Facebook job posting is disabled", null, null, null);
        }
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "repost",
                    "Only OPEN jobs can be re-posted to Facebook", null, null, null);
        }

        Optional<FacebookJobPost> existing = findActivePost(jobDescriptionId);
        if (existing.isPresent()) {
            boolean deleted = facebookPageClient.deletePost(existing.get().getFacebookPostId());
            if (!deleted) {
                return new FacebookPostOperationResponse(jobDescriptionId, false, "repost",
                        "Failed to delete old Facebook post before re-posting",
                        existing.get().getFacebookPostId(),
                        existing.get().getGeneratedContent(),
                        null);
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
                    "No active Facebook post found for this job", null, null, null);
        }

        boolean deleted = facebookPageClient.deletePost(activePost.get().getFacebookPostId());
        if (!deleted) {
            return new FacebookPostOperationResponse(jobDescriptionId, false, "delete",
                    "Failed to delete Facebook post",
                    activePost.get().getFacebookPostId(),
                    activePost.get().getGeneratedContent(),
                    null);
        }

        activePost.get().markDeleted();
        facebookJobPostRepository.save(activePost.get());
        return new FacebookPostOperationResponse(jobDescriptionId, true, "delete",
                "Facebook post deleted successfully",
                activePost.get().getFacebookPostId(),
                activePost.get().getGeneratedContent(),
                null);
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
                    generatedContent,
                    null
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
                generatedContent,
                null
        );
    }

    private FacebookPostOperationResponse withCouncilSummary(FacebookPostOperationResponse response, String councilSummary) {
        return new FacebookPostOperationResponse(
                response.jobDescriptionId(),
                response.success(),
                response.action(),
                response.message(),
                response.facebookPostId(),
                response.generatedContent(),
                councilSummary
        );
    }

    private Optional<FacebookJobPost> findActivePost(Long jobDescriptionId) {
        return facebookJobPostRepository.findFirstByJobDescriptionIdAndStatusOrderByPostedAtDesc(
                jobDescriptionId,
                FacebookPostStatus.ACTIVE
        );
    }

    private JobDescriptionUpsertRequest buildRequestFromRawText(String rawMessage, JobStatus status, Integer applicantCount) {
        if (!StringUtils.hasText(rawMessage)) {
            throw new IllegalArgumentException("Raw job content is empty");
        }

        String title = extractSection(rawMessage, "title", "role", "position", "vi tri", "vị trí", "chuc danh", "chức danh");
        if (!StringUtils.hasText(title)) {
            title = pickFirstLine(rawMessage);
        }

        String requirements = extractSection(rawMessage, "requirements", "skill", "ky nang", "kỹ năng", "yeu cau", "experience", "kinh nghiem");
        if (!StringUtils.hasText(requirements)) {
            requirements = rawMessage;
        }

        String description = extractSection(rawMessage, "description", "mo ta", "mô tả", "công việc", "job", "task", "định nghĩa");
        if (!StringUtils.hasText(description)) {
            description = rawMessage;
        }

        String salary = extractSection(rawMessage, "salary", "luong", "lương", "muc luong", "mức lương", "salary range", "mucluong");
        if (!StringUtils.hasText(salary)) {
            salary = "Thỏa thuận";
        }

        String location = extractSection(rawMessage, "location", "dia diem", "địa điểm", "tại", "tai", "location");
        if (!StringUtils.hasText(location)) {
            location = "Thương lượng";
        }

        WorkType workType = extractWorkType(rawMessage);
        return new JobDescriptionUpsertRequest(
                title,
                description,
                requirements,
                salary,
                location,
                workType,
                status == null ? JobStatus.OPEN : status,
                applicantCount == null ? 0 : applicantCount
        );
    }

    private WorkType extractWorkType(String rawMessage) {
        String lowered = rawMessage.toLowerCase(Locale.ROOT);
        boolean remote = lowered.contains("remote") || lowered.contains("from home") || lowered.contains("từ xa") || lowered.contains("từxa");
        boolean onsite = lowered.contains("onsite") || lowered.contains("on-site") || lowered.contains("văn phòng") || lowered.contains("van phong")
                || lowered.contains("offline");
        boolean hybrid = lowered.contains("hybrid") || lowered.contains("kết hợp") || lowered.contains("ket hop")
                || (remote && onsite);
        if (hybrid) {
            return WorkType.HYBRID;
        }
        if (remote) {
            return WorkType.REMOTE;
        }
        if (onsite) {
            return WorkType.ONSITE;
        }
        return WorkType.ONSITE;
    }

    private String extractSection(String rawMessage, String... labels) {
        for (String label : labels) {
            Matcher matcher = Pattern.compile("(?i)(?m)^\\s*" + Pattern.quote(label) + "\\s*[:\\-]\\s*(.+)$")
                    .matcher(rawMessage);
            if (matcher.find()) {
                String value = matcher.group(1);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private String pickFirstLine(String rawMessage) {
        String[] lines = rawMessage.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (StringUtils.hasText(trimmed)) {
                return trimmed;
            }
        }
        return rawMessage.length() <= 80 ? rawMessage : rawMessage.substring(0, 80);
    }
}
