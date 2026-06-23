package com.example.recruitmentbot.facebookgroup.service;

import com.example.recruitmentbot.config.FacebookGroupPostingProperties;
import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostHistory;
import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupPostStatus;
import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupTarget;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostAttemptResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostHistoryResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetRequest;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetResponse;
import com.example.recruitmentbot.facebookgroup.repository.FacebookGroupPostHistoryRepository;
import com.example.recruitmentbot.facebookgroup.repository.FacebookGroupTargetRepository;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.repository.JobDescriptionRepository;
import com.example.recruitmentbot.jobposting.service.JobDescriptionService;
import com.example.recruitmentbot.jobposting.service.JobPostContentGenerator;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FacebookGroupPostingService {

    private static final Logger log = LoggerFactory.getLogger(FacebookGroupPostingService.class);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");

    private final FacebookGroupTargetRepository groupTargetRepository;
    private final FacebookGroupPostHistoryRepository historyRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final JobDescriptionService jobDescriptionService;
    private final JobPostContentGenerator jobPostContentGenerator;
    private final FacebookGroupRpaClient facebookGroupRpaClient;
    private final FacebookGroupPostingProperties properties;

    public FacebookGroupPostingService(
            FacebookGroupTargetRepository groupTargetRepository,
            FacebookGroupPostHistoryRepository historyRepository,
            JobDescriptionRepository jobDescriptionRepository,
            JobDescriptionService jobDescriptionService,
            JobPostContentGenerator jobPostContentGenerator,
            FacebookGroupRpaClient facebookGroupRpaClient,
            FacebookGroupPostingProperties properties
    ) {
        this.groupTargetRepository = groupTargetRepository;
        this.historyRepository = historyRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.jobDescriptionService = jobDescriptionService;
        this.jobPostContentGenerator = jobPostContentGenerator;
        this.facebookGroupRpaClient = facebookGroupRpaClient;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<FacebookGroupTargetResponse> listGroups() {
        return groupTargetRepository.findAllByOrderByPriorityOrderAscCreatedAtAsc().stream()
                .map(this::toGroupResponse)
                .toList();
    }

    @Transactional
    public FacebookGroupTargetResponse createGroup(FacebookGroupTargetRequest request) {
        FacebookGroupTarget target = new FacebookGroupTarget();
        apply(target, request);
        FacebookGroupTarget saved = groupTargetRepository.save(target);
        log.info("Created Facebook group target. groupId={}, name={}, reference={}",
                saved.getId(), saved.getDisplayName(), saved.getGroupReference());
        return toGroupResponse(saved);
    }

    @Transactional
    public FacebookGroupTargetResponse updateGroup(Long groupId, FacebookGroupTargetRequest request) {
        FacebookGroupTarget target = getGroup(groupId);
        apply(target, request);
        FacebookGroupTarget saved = groupTargetRepository.save(target);
        log.info("Updated Facebook group target. groupId={}, active={}", saved.getId(), saved.getActive());
        return toGroupResponse(saved);
    }

    @Transactional
    public FacebookGroupTargetResponse deactivateGroup(Long groupId) {
        FacebookGroupTarget target = getGroup(groupId);
        target.setActive(false);
        FacebookGroupTarget saved = groupTargetRepository.save(target);
        log.info("Deactivated Facebook group target. groupId={}, name={}", saved.getId(), saved.getDisplayName());
        return toGroupResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<FacebookGroupPostHistoryResponse> listRecentHistory() {
        return historyRepository.findTop50ByOrderByPostedAtDesc().stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public String buildGroupSelectionSummary() {
        List<FacebookGroupTarget> groups = groupTargetRepository.findAllByOrderByPriorityOrderAscCreatedAtAsc();
        if (groups.isEmpty()) {
            return "Chua co Facebook Group nao trong database.\n"
                    + "Them group theo format: them group <ten group> | <group id hoac url>";
        }

        StringBuilder builder = new StringBuilder("Danh sach Facebook Group:\n");
        groups.forEach(group -> builder
                .append(group.getId())
                .append(". ")
                .append(group.getDisplayName())
                .append(" | ")
                .append(Boolean.TRUE.equals(group.getActive()) ? "ACTIVE" : "INACTIVE")
                .append(" | ")
                .append(group.getGroupReference())
                .append('\n'));
        builder.append("Dang 1 group: dang group job <jobId> group <groupId>\n");
        builder.append("Dang tat ca group ACTIVE: dang group <jobId>");
        return builder.toString();
    }

    public FacebookGroupPostSummaryResponse publishJobToActiveGroups(Long jobDescriptionId, String triggeredBySenderId, String triggerSource) {
        log.info("Facebook group posting triggered. source={}, senderId={}, jobId={}",
                triggerSource, triggeredBySenderId, jobDescriptionId);

        if (!properties.enabled()) {
            return emptySummary(jobDescriptionId, null, false, "Facebook group posting is disabled");
        }

        JobDescription jobDescription = jobDescriptionService.getEntity(jobDescriptionId);
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return emptySummary(jobDescriptionId, jobDescription.getTitle(), false, "Only OPEN jobs can be posted to Facebook Groups");
        }

        List<FacebookGroupTarget> activeGroups = groupTargetRepository.findAllByActiveTrueOrderByPriorityOrderAscCreatedAtAsc();
        if (activeGroups.isEmpty()) {
            log.warn("Facebook group posting skipped because no active groups are configured. jobId={}", jobDescriptionId);
            return emptySummary(jobDescriptionId, jobDescription.getTitle(), false, "No active Facebook Groups are configured");
        }

        return publishJobToGroups(jobDescription, activeGroups);
    }

    public FacebookGroupPostSummaryResponse publishJobToSelectedGroups(
            Long jobDescriptionId,
            List<Long> groupIds,
            String triggeredBySenderId,
            String triggerSource
    ) {
        log.info("Facebook selected group posting triggered. source={}, senderId={}, jobId={}, groupIds={}",
                triggerSource, triggeredBySenderId, jobDescriptionId, groupIds);

        if (!properties.enabled()) {
            return emptySummary(jobDescriptionId, null, false, "Facebook group posting is disabled");
        }
        if (groupIds == null || groupIds.isEmpty()) {
            return emptySummary(jobDescriptionId, null, false, "No Facebook Group was selected");
        }

        JobDescription jobDescription = jobDescriptionService.getEntity(jobDescriptionId);
        if (jobDescription.getStatus() != JobStatus.OPEN) {
            return emptySummary(jobDescriptionId, jobDescription.getTitle(), false, "Only OPEN jobs can be posted to Facebook Groups");
        }

        List<FacebookGroupTarget> selectedGroups = groupTargetRepository
                .findAllByIdInAndActiveTrueOrderByPriorityOrderAscCreatedAtAsc(groupIds);
        if (selectedGroups.isEmpty()) {
            return emptySummary(jobDescriptionId, jobDescription.getTitle(), false, "No selected ACTIVE Facebook Groups were found");
        }

        return publishJobToGroups(jobDescription, selectedGroups);
    }

    private FacebookGroupPostSummaryResponse publishJobToGroups(
            JobDescription jobDescription,
            List<FacebookGroupTarget> targetGroups
    ) {
        Long jobDescriptionId = jobDescription.getId();
        String generatedContent;
        try {
            generatedContent = jobPostContentGenerator.generatePost(jobDescription);
        } catch (Exception exception) {
            log.error("Failed to generate Facebook group post content. jobId={}", jobDescriptionId, exception);
            return emptySummary(jobDescriptionId, jobDescription.getTitle(), false,
                    "Failed to generate Facebook group post content: " + exception.getMessage());
        }
        log.info("Generated Facebook group post content. jobId={}, contentLength={}",
                jobDescriptionId, generatedContent == null ? 0 : generatedContent.length());

        List<FacebookGroupPostAttemptResponse> attempts = new ArrayList<>();
        for (int index = 0; index < targetGroups.size(); index++) {
            FacebookGroupTarget target = targetGroups.get(index);
            if (isDailyLimitReached(target)) {
                String reason = "Daily post limit reached for this group";
                FacebookGroupPostHistory skipped = createPendingHistory(jobDescription, target, generatedContent);
                skipped.markSkipped(reason);
                FacebookGroupPostHistory saved = historyRepository.save(skipped);
                attempts.add(toAttemptResponse(saved, target));
                log.warn("Skipping Facebook group because daily limit was reached. groupId={}, groupName={}, jobId={}",
                        target.getId(), target.getDisplayName(), jobDescriptionId);
                continue;
            }

            if (index > 0) {
                delayBeforeNextGroup(target);
            }

            attempts.add(postWithRetry(jobDescription, target, generatedContent));
        }

        int successCount = (int) attempts.stream().filter(attempt -> attempt.status() == FacebookGroupPostStatus.SUCCESS).count();
        int failedCount = (int) attempts.stream().filter(attempt -> attempt.status() == FacebookGroupPostStatus.FAILED).count();
        int skippedCount = (int) attempts.stream().filter(attempt -> attempt.status() == FacebookGroupPostStatus.SKIPPED).count();
        boolean success = failedCount == 0 && successCount > 0;
        String message = "Facebook Group posting completed: success=" + successCount
                + ", failed=" + failedCount
                + ", skipped=" + skippedCount;

        log.info("Facebook group posting completed. jobId={}, successCount={}, failedCount={}, skippedCount={}",
                jobDescriptionId, successCount, failedCount, skippedCount);
        return new FacebookGroupPostSummaryResponse(
                jobDescriptionId,
                jobDescription.getTitle(),
                success,
                message,
                targetGroups.size(),
                successCount,
                failedCount,
                skippedCount,
                generatedContent,
                attempts
        );
    }

    public List<FacebookGroupPostSummaryResponse> publishOpenJobsByScheduler() {
        if (!properties.enabled() || !properties.schedulerEnabled()) {
            return List.of();
        }

        List<JobDescription> openJobs = jobDescriptionRepository.findAllByStatusOrderByCreatedAtDesc(JobStatus.OPEN);
        log.info("Scheduled Facebook group posting started. openJobCount={}", openJobs.size());
        List<FacebookGroupPostSummaryResponse> summaries = new ArrayList<>();
        for (JobDescription openJob : openJobs) {
            try {
                summaries.add(publishJobToActiveGroups(openJob.getId(), null, "scheduler"));
            } catch (Exception exception) {
                log.error("Scheduled Facebook group posting failed. jobId={}", openJob.getId(), exception);
            }
        }
        log.info("Scheduled Facebook group posting finished. processedJobCount={}", summaries.size());
        return summaries;
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveOpenJobIdFromMessage(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }

        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            Long candidateId = Long.parseLong(matcher.group(1));
            Optional<JobDescription> candidate = jobDescriptionRepository.findById(candidateId);
            if (candidate.isPresent() && candidate.get().getStatus() == JobStatus.OPEN) {
                return Optional.of(candidateId);
            }
        }

        String normalizedText = normalizeText(text);
        List<JobDescription> openJobs = jobDescriptionRepository.findAllByStatusOrderByCreatedAtDesc(JobStatus.OPEN);
        List<JobDescription> matches = openJobs.stream()
                .filter(job -> titleMatches(normalizedText, job.getTitle()))
                .sorted(Comparator.comparing(JobDescription::getCreatedAt).reversed())
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0).getId()) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public String buildOpenJobSelectionPrompt() {
        List<JobDescription> openJobs = jobDescriptionRepository.findAllByStatusOrderByCreatedAtDesc(JobStatus.OPEN);
        if (openJobs.isEmpty()) {
            return "Chua co JD OPEN nao trong database de dang group.";
        }

        StringBuilder builder = new StringBuilder("Hay chon JD can dang group:\n");
        openJobs.stream().limit(8).forEach(job -> builder
                .append(job.getId())
                .append(". ")
                .append(job.getTitle())
                .append(" - ")
                .append(job.getLocation())
                .append('\n'));
        builder.append("Tra loi bang jobId, vi du: 3");
        return builder.toString();
    }

    public String buildMessengerSummary(FacebookGroupPostSummaryResponse summary) {
        StringBuilder builder = new StringBuilder();
        builder.append("Ket qua dang Facebook Group cho JD ");
        builder.append(summary.jobDescriptionId());
        if (StringUtils.hasText(summary.jobTitle())) {
            builder.append(" - ").append(summary.jobTitle());
        }
        builder.append(":\n");
        builder.append("Thanh cong: ").append(summary.successCount()).append('/').append(summary.totalGroups()).append('\n');
        builder.append("That bai: ").append(summary.failedCount()).append('\n');
        builder.append("Bo qua: ").append(summary.skippedCount()).append('\n');

        List<FacebookGroupPostAttemptResponse> failedAttempts = summary.attempts().stream()
                .filter(attempt -> attempt.status() == FacebookGroupPostStatus.FAILED
                        || attempt.status() == FacebookGroupPostStatus.SKIPPED)
                .toList();
        if (!failedAttempts.isEmpty()) {
            builder.append("Group can xem lai:\n");
            failedAttempts.forEach(attempt -> builder
                    .append("- ")
                    .append(attempt.groupName())
                    .append(": ")
                    .append(attempt.errorReason())
                    .append('\n'));
        }
        return builder.toString().trim();
    }

    private FacebookGroupPostAttemptResponse postWithRetry(
            JobDescription jobDescription,
            FacebookGroupTarget target,
            String generatedContent
    ) {
        FacebookGroupPostHistory history = createPendingHistory(jobDescription, target, generatedContent);
        history = historyRepository.save(history);

        String lastError = null;
        String lastRawResponse = null;
        int retryCount = 0;
        int maxAttempts = properties.effectiveRetryCount() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            retryCount = attempt - 1;
            try {
                log.info("Calling OpenClaw RPA for Facebook group post. jobId={}, groupId={}, attempt={}/{}",
                        jobDescription.getId(), target.getId(), attempt, maxAttempts);
                FacebookGroupRpaResult result = facebookGroupRpaClient.postToGroup(new FacebookGroupRpaRequest(
                        jobDescription.getId(),
                        target.getId(),
                        target.getDisplayName(),
                        target.getGroupReference(),
                        generatedContent,
                        properties.effectiveOpenClawScriptName()
                ));
                lastRawResponse = result.rawResponse();
                if (result.success()) {
                    history.markSuccess(lastRawResponse, retryCount);
                    FacebookGroupPostHistory saved = historyRepository.save(history);
                    log.info("Facebook group post succeeded. jobId={}, groupId={}, retryCount={}",
                            jobDescription.getId(), target.getId(), retryCount);
                    return toAttemptResponse(saved, target);
                }
                lastError = result.message();
            } catch (Exception exception) {
                lastError = exception.getMessage();
                log.warn("Facebook group post attempt failed. jobId={}, groupId={}, attempt={}/{}",
                        jobDescription.getId(), target.getId(), attempt, maxAttempts, exception);
            }
        }

        history.markFailed(lastError, lastRawResponse, retryCount);
        FacebookGroupPostHistory saved = historyRepository.save(history);
        log.error("Facebook group post failed after retries. jobId={}, groupId={}, retryCount={}, error={}",
                jobDescription.getId(), target.getId(), retryCount, lastError);
        return toAttemptResponse(saved, target);
    }

    private FacebookGroupPostHistory createPendingHistory(
            JobDescription jobDescription,
            FacebookGroupTarget target,
            String generatedContent
    ) {
        FacebookGroupPostHistory history = new FacebookGroupPostHistory();
        history.setJobDescription(jobDescription);
        history.setTargetGroup(target);
        history.setGeneratedContent(generatedContent);
        history.setStatus(FacebookGroupPostStatus.PENDING);
        history.setPostedAt(OffsetDateTime.now());
        history.setRetryCount(0);
        return history;
    }

    private boolean isDailyLimitReached(FacebookGroupTarget target) {
        ZoneId zoneId = ZoneId.systemDefault();
        OffsetDateTime start = LocalDate.now(zoneId).atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);
        long successCount = historyRepository.countByTargetGroupIdAndStatusAndPostedAtBetween(
                target.getId(),
                FacebookGroupPostStatus.SUCCESS,
                start,
                end
        );
        return successCount >= properties.effectiveMaxPostsPerDayPerGroup();
    }

    private void delayBeforeNextGroup(FacebookGroupTarget target) {
        int min = properties.effectiveDelayMinSeconds();
        int max = properties.effectiveDelayMaxSeconds();
        int delaySeconds = max <= min ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        if (delaySeconds <= 0) {
            return;
        }
        log.info("Waiting before next Facebook group post. groupId={}, delaySeconds={}", target.getId(), delaySeconds);
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Facebook group posting was interrupted during configured delay", exception);
        }
    }

    private FacebookGroupPostSummaryResponse emptySummary(Long jobId, String jobTitle, boolean success, String message) {
        return new FacebookGroupPostSummaryResponse(jobId, jobTitle, success, message, 0, 0, 0, 0, null, List.of());
    }

    private FacebookGroupTarget getGroup(Long groupId) {
        return groupTargetRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facebook group target not found: " + groupId));
    }

    private void apply(FacebookGroupTarget target, FacebookGroupTargetRequest request) {
        target.setDisplayName(request.displayName().trim());
        target.setGroupReference(request.groupReference().trim());
        target.setActive(request.active());
        target.setPriorityOrder(request.priorityOrder());
    }

    private FacebookGroupTargetResponse toGroupResponse(FacebookGroupTarget target) {
        return new FacebookGroupTargetResponse(
                target.getId(),
                target.getDisplayName(),
                target.getGroupReference(),
                target.getActive(),
                target.getPriorityOrder(),
                target.getCreatedAt(),
                target.getUpdatedAt()
        );
    }

    private FacebookGroupPostAttemptResponse toAttemptResponse(FacebookGroupPostHistory history) {
        return toAttemptResponse(history, history.getTargetGroup());
    }

    private FacebookGroupPostAttemptResponse toAttemptResponse(FacebookGroupPostHistory history, FacebookGroupTarget target) {
        return new FacebookGroupPostAttemptResponse(
                history.getId(),
                target.getId(),
                target.getDisplayName(),
                target.getGroupReference(),
                history.getStatus(),
                history.getRetryCount(),
                history.getErrorReason()
        );
    }

    private FacebookGroupPostHistoryResponse toHistoryResponse(FacebookGroupPostHistory history) {
        return new FacebookGroupPostHistoryResponse(
                history.getId(),
                history.getJobDescription().getId(),
                history.getJobDescription().getTitle(),
                history.getTargetGroup().getId(),
                history.getTargetGroup().getDisplayName(),
                history.getTargetGroup().getGroupReference(),
                history.getPostedAt(),
                history.getStatus(),
                history.getRetryCount(),
                history.getErrorReason()
        );
    }

    private boolean titleMatches(String normalizedText, String title) {
        String normalizedTitle = normalizeText(title);
        if (!StringUtils.hasText(normalizedTitle)) {
            return false;
        }
        if (normalizedText.contains(normalizedTitle)) {
            return true;
        }
        String[] titleTokens = normalizedTitle.split("\\s+");
        int matchedTokens = 0;
        for (String token : titleTokens) {
            if (token.length() >= 3 && normalizedText.contains(token)) {
                matchedTokens++;
            }
        }
        return matchedTokens > 0 && titleTokens.length <= 2;
    }

    private String normalizeText(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "").toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }
}
