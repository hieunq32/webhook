package com.example.recruitmentbot.integration.facebookgroup.service;

import com.example.recruitmentbot.facebookgroup.domain.FacebookGroupTarget;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import com.example.recruitmentbot.facebookgroup.repository.FacebookGroupTargetRepository;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupRpaClient;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupRpaSessionStatus;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupPostingService;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupPostRequest;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupPostResponse;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupSessionImportRequest;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalFacebookGroupTargetRequest;
import com.example.recruitmentbot.integration.facebookgroup.dto.ExternalJobDescriptionRequest;
import com.example.recruitmentbot.integration.facebookgroup.dto.ResolvedFacebookGroupTargetResponse;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.JobStatus;
import com.example.recruitmentbot.jobposting.domain.WorkType;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionUpsertRequest;
import com.example.recruitmentbot.jobposting.service.JobDescriptionService;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExternalFacebookGroupPostService {

    private static final Logger log = LoggerFactory.getLogger(ExternalFacebookGroupPostService.class);
    private static final Pattern GROUP_PATH_PATTERN = Pattern.compile("/groups/([^/?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FACEBOOK_NUMERIC_ID_PATTERN = Pattern.compile("^\\d{6,}$");

    private final JobDescriptionService jobDescriptionService;
    private final FacebookGroupTargetRepository groupTargetRepository;
    private final FacebookGroupPostingService facebookGroupPostingService;
    private final FacebookGroupRpaClient facebookGroupRpaClient;

    public ExternalFacebookGroupPostService(
            JobDescriptionService jobDescriptionService,
            FacebookGroupTargetRepository groupTargetRepository,
            FacebookGroupPostingService facebookGroupPostingService,
            FacebookGroupRpaClient facebookGroupRpaClient
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.groupTargetRepository = groupTargetRepository;
        this.facebookGroupPostingService = facebookGroupPostingService;
        this.facebookGroupRpaClient = facebookGroupRpaClient;
    }

    public ExternalFacebookGroupPostResponse createJobAndPostToGroups(ExternalFacebookGroupPostRequest request) {
        FacebookGroupRpaSessionStatus sessionStatus = facebookGroupRpaClient.getSessionStatus();
        if (!sessionStatus.sessionExists()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Facebook login is required before posting to groups. Start login at "
                            + sessionStatus.loginStartUrl()
                            + " and complete it at "
                            + sessionStatus.loginCompleteUrl()
            );
        }

        JobDescription jobDescription = jobDescriptionService.createEntity(toUpsertRequest(request.jobDescription()));
        List<FacebookGroupTarget> targets = resolveTargets(request.targetGroups());
        List<Long> targetIds = targets.stream().map(FacebookGroupTarget::getId).toList();

        log.info("External Facebook group posting request accepted. externalRequestId={}, jobId={}, groupIds={}",
                request.externalRequestId(), jobDescription.getId(), targetIds);

        FacebookGroupPostSummaryResponse summary = facebookGroupPostingService.publishJobToSelectedGroups(
                jobDescription.getId(),
                targetIds,
                null,
                "external-api"
        );
        return new ExternalFacebookGroupPostResponse(
                request.externalRequestId(),
                jobDescription.getId(),
                jobDescription.getTitle(),
                targets.stream().map(this::toResolvedResponse).toList(),
                summary
        );
    }

    public FacebookGroupRpaSessionStatus getSessionStatus() {
        return facebookGroupRpaClient.getSessionStatus();
    }

    public FacebookGroupRpaSessionStatus startLogin() {
        return facebookGroupRpaClient.startLogin();
    }

    public FacebookGroupRpaSessionStatus completeLogin() {
        return facebookGroupRpaClient.completeLogin();
    }

    public FacebookGroupRpaSessionStatus importSession(ExternalFacebookGroupSessionImportRequest request) {
        log.info("Importing Facebook browser session for external integration. sessionOwnerKey={}", request.sessionOwnerKey());
        return facebookGroupRpaClient.importSession(request.storageState());
    }

    private JobDescriptionUpsertRequest toUpsertRequest(ExternalJobDescriptionRequest request) {
        return new JobDescriptionUpsertRequest(
                request.title().trim(),
                request.description().trim(),
                request.requirements().trim(),
                request.salary().trim(),
                request.location().trim(),
                parseWorkType(request.workType()),
                parseJobStatus(request.status()),
                0
        );
    }

    private List<FacebookGroupTarget> resolveTargets(List<ExternalFacebookGroupTargetRequest> requests) {
        Map<String, ExternalFacebookGroupTargetRequest> uniqueTargets = new LinkedHashMap<>();
        for (ExternalFacebookGroupTargetRequest request : requests) {
            String reference = normalizeGroupReference(request);
            uniqueTargets.putIfAbsent(reference, request);
        }

        List<FacebookGroupTarget> targets = new ArrayList<>();
        for (Map.Entry<String, ExternalFacebookGroupTargetRequest> entry : uniqueTargets.entrySet()) {
            targets.add(upsertGroupTarget(entry.getKey(), entry.getValue()));
        }
        return targets;
    }

    private FacebookGroupTarget upsertGroupTarget(String groupReference, ExternalFacebookGroupTargetRequest request) {
        return groupTargetRepository.findByGroupReference(groupReference)
                .map(existing -> {
                    boolean changed = false;
                    String displayName = displayName(request, groupReference);
                    if (StringUtils.hasText(displayName) && !displayName.equals(existing.getDisplayName())) {
                        existing.setDisplayName(displayName);
                        changed = true;
                    }
                    if (!Boolean.TRUE.equals(existing.getActive())) {
                        existing.setActive(true);
                        changed = true;
                    }
                    return changed ? groupTargetRepository.save(existing) : existing;
                })
                .orElseGet(() -> {
                    FacebookGroupTarget target = new FacebookGroupTarget();
                    target.setDisplayName(displayName(request, groupReference));
                    target.setGroupReference(groupReference);
                    target.setActive(true);
                    target.setPriorityOrder(100);
                    FacebookGroupTarget saved = groupTargetRepository.save(target);
                    log.info("Created Facebook group target from external request. internalGroupId={}, reference={}",
                            saved.getId(), saved.getGroupReference());
                    return saved;
                });
    }

    private String normalizeGroupReference(ExternalFacebookGroupTargetRequest request) {
        String raw = firstNonBlank(request.facebookGroupUrl(), request.facebookGroupId(), request.groupId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Each target group must contain groupId, facebookGroupId, or facebookGroupUrl"
                ));
        String value = raw.trim();
        if (FACEBOOK_NUMERIC_ID_PATTERN.matcher(value).matches()) {
            return "https://www.facebook.com/groups/" + value;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://") && value.contains("facebook.com")) {
            value = "https://" + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = URI.create(value);
                Matcher matcher = GROUP_PATH_PATTERN.matcher(uri.getPath());
                if (matcher.find()) {
                    return "https://www.facebook.com/groups/" + matcher.group(1);
                }
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Facebook group URL: " + raw);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Facebook group identifier: " + raw);
    }

    private String displayName(ExternalFacebookGroupTargetRequest request, String groupReference) {
        if (StringUtils.hasText(request.groupName())) {
            return request.groupName().trim();
        }
        Matcher matcher = GROUP_PATH_PATTERN.matcher(URI.create(groupReference).getPath());
        return matcher.find() ? "Facebook Group " + matcher.group(1) : "Facebook Group";
    }

    private WorkType parseWorkType(String value) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            return WorkType.ONSITE;
        }
        if (normalized.contains("remote") || normalized.contains("tu xa") || normalized.contains("online")) {
            return WorkType.REMOTE;
        }
        if (normalized.contains("hybrid") || normalized.contains("linh hoat")) {
            return WorkType.HYBRID;
        }
        if (normalized.contains("onsite") || normalized.contains("van phong") || normalized.contains("office")) {
            return WorkType.ONSITE;
        }
        return parseEnum(value, WorkType.class, "workType");
    }

    private JobStatus parseJobStatus(String value) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            return JobStatus.OPEN;
        }
        if (normalized.contains("open") || normalized.contains("dang tuyen") || normalized.contains("mo")) {
            return JobStatus.OPEN;
        }
        if (normalized.contains("filled") || normalized.contains("da tuyen") || normalized.contains("du nguoi")) {
            return JobStatus.FILLED;
        }
        if (normalized.contains("closed") || normalized.contains("dong") || normalized.contains("ngung")) {
            return JobStatus.CLOSED;
        }
        return parseEnum(value, JobStatus.class, "status");
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> enumType, String fieldName) {
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + fieldName + ": " + value);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String noAccent = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return noAccent.toLowerCase(Locale.ROOT).trim();
    }

    private java.util.Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }

    private ResolvedFacebookGroupTargetResponse toResolvedResponse(FacebookGroupTarget target) {
        return new ResolvedFacebookGroupTargetResponse(
                target.getId(),
                target.getDisplayName(),
                target.getGroupReference()
        );
    }
}
