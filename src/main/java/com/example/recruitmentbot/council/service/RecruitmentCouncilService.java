package com.example.recruitmentbot.council.service;

import com.example.recruitmentbot.council.domain.CouncilHiringRequest;
import com.example.recruitmentbot.council.domain.CouncilHiringRequestStatus;
import com.example.recruitmentbot.council.domain.JobDescriptionCouncil;
import com.example.recruitmentbot.council.domain.RecruitmentCouncil;
import com.example.recruitmentbot.council.repository.CouncilHiringRequestRepository;
import com.example.recruitmentbot.council.repository.JobDescriptionCouncilRepository;
import com.example.recruitmentbot.council.repository.RecruitmentCouncilRepository;
import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class RecruitmentCouncilService {

    private static final Pattern COUNCIL_CODE_PATTERN = Pattern.compile(
            "(?i)(?:ma\\s*hd|ma\\s*hoi\\s*dong|hd|council)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9_\\-]{1,20})"
    );
    private static final Pattern COUNCIL_NAME_PATTERN = Pattern.compile(
            "(?i)(?:ten\\s*hoi\\s*dong|hoi\\s*dong|council\\s*name)\\s*[:\\-]?\\s*([^\\n,;]+)"
    );

    private final RecruitmentCouncilRepository councilRepository;
    private final JobDescriptionCouncilRepository jobCouncilRepository;
    private final CouncilHiringRequestRepository requestRepository;

    public RecruitmentCouncilService(
            RecruitmentCouncilRepository councilRepository,
            JobDescriptionCouncilRepository jobCouncilRepository,
            CouncilHiringRequestRepository requestRepository
    ) {
        this.councilRepository = councilRepository;
        this.jobCouncilRepository = jobCouncilRepository;
        this.requestRepository = requestRepository;
    }

    @Transactional
    public CouncilAssignmentResult assignCouncilsFromRawMessage(Long jobDescriptionId, String rawMessage, PageAdminAccount fallbackOwner) {
        List<CouncilRef> refs = extractCouncilRefs(rawMessage);
        jobCouncilRepository.deleteAllByJobDescriptionId(jobDescriptionId);

        List<RecruitmentCouncil> councils = new ArrayList<>();
        for (CouncilRef ref : refs) {
            RecruitmentCouncil council = findOrCreateCouncil(ref, fallbackOwner);
            JobDescriptionCouncil link = new JobDescriptionCouncil();
            link.setJobDescriptionId(jobDescriptionId);
            link.setCouncilId(council.getId());
            jobCouncilRepository.save(link);
            councils.add(council);
        }

        return new CouncilAssignmentResult(stripCouncilMetadata(rawMessage), buildCouncilSummary(councils));
    }

    @Transactional(readOnly = true)
    public List<RecruitmentCouncil> findCouncilsForJob(Long jobDescriptionId) {
        List<Long> ids = jobCouncilRepository.findAllByJobDescriptionId(jobDescriptionId).stream()
                .map(JobDescriptionCouncil::getCouncilId)
                .toList();
        return ids.isEmpty() ? List.of() : councilRepository.findAllByIdInAndActiveTrue(ids);
    }

    @Transactional(readOnly = true)
    public RecruitmentCouncil findByRepresentativeSenderId(String senderId) {
        return councilRepository.findFirstByRepresentativeSenderIdAndActiveTrue(senderId).orElse(null);
    }

    @Transactional(readOnly = true)
    public RecruitmentCouncil findActiveCouncilByReference(String reference) {
        if (!StringUtils.hasText(reference)) {
            return null;
        }

        String trimmedReference = reference.trim();
        RecruitmentCouncil exactCodeMatch = councilRepository.findFirstByCodeIgnoreCase(trimmedReference).orElse(null);
        if (exactCodeMatch != null && exactCodeMatch.isActive()) {
            return exactCodeMatch;
        }

        RecruitmentCouncil exactNameMatch = councilRepository.findFirstByNameIgnoreCase(trimmedReference).orElse(null);
        if (exactNameMatch != null && exactNameMatch.isActive()) {
            return exactNameMatch;
        }

        String normalizedReference = normalizeCouncilReference(trimmedReference);
        String compactReference = compactCouncilReference(trimmedReference);
        for (RecruitmentCouncil council : councilRepository.findAll()) {
            if (!council.isActive()) {
                continue;
            }
            String normalizedCode = normalizeCouncilReference(council.getCode());
            String normalizedName = normalizeCouncilReference(council.getName());
            String compactCode = compactCouncilReference(council.getCode());
            String compactName = compactCouncilReference(council.getName());
            if (normalizedReference.equals(normalizedCode)
                    || compactReference.equals(compactCode)
                    || normalizedReference.equals(normalizedName)
                    || compactReference.equals(compactName)
                    || normalizedName.contains(normalizedReference)) {
                return council;
            }
        }
        return null;
    }

    @Transactional
    public void ensureCouncilMappedToJob(Long jobDescriptionId, RecruitmentCouncil council) {
        if (jobDescriptionId == null || council == null) {
            return;
        }
        boolean exists = jobCouncilRepository.findAllByJobDescriptionId(jobDescriptionId).stream()
                .anyMatch(link -> council.getId().equals(link.getCouncilId()));
        if (exists) {
            return;
        }
        JobDescriptionCouncil link = new JobDescriptionCouncil();
        link.setJobDescriptionId(jobDescriptionId);
        link.setCouncilId(council.getId());
        jobCouncilRepository.save(link);
    }

    @Transactional(readOnly = true)
    public String buildActiveCouncilSummary() {
        List<RecruitmentCouncil> activeCouncils = councilRepository.findAll().stream()
                .filter(RecruitmentCouncil::isActive)
                .sorted((left, right) -> left.getName().compareToIgnoreCase(right.getName()))
                .toList();
        if (activeCouncils.isEmpty()) {
            return "Hien tai khong co Hoi dong active trong he thong.";
        }

        StringBuilder builder = new StringBuilder("Danh sach Hoi dong:\n");
        for (RecruitmentCouncil council : activeCouncils) {
            builder.append("Ma HD: ").append(council.getCode())
                    .append(" | Ten: ").append(council.getName())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    @Transactional
    public CouncilHiringRequest createHiringRequest(PageAdminAccount councilAccount, String content) {
        RecruitmentCouncil council = councilRepository.findFirstByRepresentativeSenderIdAndActiveTrue(councilAccount.getSenderId())
                .orElseGet(() -> createCouncilFromAccount(councilAccount));
        CouncilHiringRequest request = new CouncilHiringRequest();
        request.setCouncilId(council.getId());
        request.setCouncilCode(council.getCode());
        request.setCouncilName(council.getName());
        request.setRequesterSenderId(councilAccount.getSenderId());
        request.setRequestContent(content.trim());
        request.setStatus(CouncilHiringRequestStatus.PENDING);
        return requestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public String buildPendingHiringRequestsSummary() {
        List<CouncilHiringRequest> requests = requestRepository.findAllByStatusOrderByCreatedAtDesc(CouncilHiringRequestStatus.PENDING);
        if (requests.isEmpty()) {
            return "Hien tai khong co request tu Hoi dong.";
        }

        StringBuilder builder = new StringBuilder("Request tu Hoi dong:\n");
        for (CouncilHiringRequest request : requests) {
            builder.append("[requestId=").append(request.getId()).append("] ")
                    .append("Ma HD: ").append(request.getCouncilCode()).append('\n')
                    .append("Ten: ").append(request.getCouncilName()).append('\n')
                    .append("Noi dung: ").append(request.getRequestContent()).append("\n\n");
        }
        return builder.toString().trim();
    }

    public String buildCouncilSummary(List<RecruitmentCouncil> councils) {
        if (councils == null || councils.isEmpty()) {
            return "Chua gan Hoi dong.";
        }
        StringBuilder builder = new StringBuilder();
        for (RecruitmentCouncil council : councils) {
            builder.append("Ma HD: ").append(council.getCode()).append('\n')
                    .append("Ten: ").append(council.getName()).append('\n');
        }
        return builder.toString().trim();
    }

    private RecruitmentCouncil findOrCreateCouncil(CouncilRef ref, PageAdminAccount fallbackOwner) {
        String code = StringUtils.hasText(ref.code()) ? ref.code().trim().toUpperCase(Locale.ROOT) : null;
        String name = StringUtils.hasText(ref.name()) ? ref.name().trim() : null;

        RecruitmentCouncil existing = null;
        if (StringUtils.hasText(code)) {
            existing = councilRepository.findFirstByCodeIgnoreCase(code).orElse(null);
        }
        if (existing == null && StringUtils.hasText(name)) {
            existing = councilRepository.findFirstByNameIgnoreCase(name).orElse(null);
        }
        if (existing != null) {
            return existing;
        }

        RecruitmentCouncil council = new RecruitmentCouncil();
        council.setCode(StringUtils.hasText(code) ? code : generateCode(name));
        council.setName(StringUtils.hasText(name) ? name : "Hoi dong " + council.getCode());
        council.setRepresentativeSenderId(fallbackOwner == null ? "SYSTEM" : fallbackOwner.getSenderId());
        council.setInterviewerOne(fallbackOwner == null ? null : fallbackOwner.getDisplayName());
        council.setActive(true);
        return councilRepository.save(council);
    }

    private RecruitmentCouncil createCouncilFromAccount(PageAdminAccount account) {
        RecruitmentCouncil council = new RecruitmentCouncil();
        council.setCode(generateCode(account.getDisplayName()));
        council.setName(StringUtils.hasText(account.getDisplayName()) ? account.getDisplayName() : "Hoi dong " + account.getSenderId());
        council.setRepresentativeSenderId(account.getSenderId());
        council.setInterviewerOne(account.getDisplayName());
        council.setActive(true);
        return councilRepository.save(council);
    }

    private List<CouncilRef> extractCouncilRefs(String rawMessage) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher codeMatcher = COUNCIL_CODE_PATTERN.matcher(normalizeAccents(rawMessage));
        while (codeMatcher.find()) {
            codes.add(codeMatcher.group(1).trim().toUpperCase(Locale.ROOT));
        }

        String name = null;
        Matcher nameMatcher = COUNCIL_NAME_PATTERN.matcher(normalizeAccents(rawMessage));
        if (nameMatcher.find()) {
            name = nameMatcher.group(1).trim();
        }

        if (codes.isEmpty() && StringUtils.hasText(name)) {
            return List.of(new CouncilRef(null, name));
        }
        if (codes.isEmpty()) {
            return List.of();
        }

        List<CouncilRef> refs = new ArrayList<>();
        for (String code : codes) {
            refs.add(new CouncilRef(code, name));
        }
        return refs;
    }

    private String stripCouncilMetadata(String rawMessage) {
        String cleaned = rawMessage
                .replaceAll("(?iu)(ma\\s*hd|mã\\s*hd|ma\\s*hoi\\s*dong|mã\\s*hội\\s*đồng|hd|council)\\s*[:\\-]?\\s*[A-Z0-9][A-Z0-9_\\-]{1,20}", "")
                .replaceAll("(?iu)(ten\\s*hoi\\s*dong|tên\\s*hội\\s*đồng|hoi\\s*dong|hội\\s*đồng|council\\s*name)\\s*[:\\-]?\\s*[^\\n,;]+", "")
                .replaceAll("[,;]{2,}", ",")
                .trim();
        return StringUtils.hasText(cleaned) ? cleaned : rawMessage;
    }

    private String generateCode(String seed) {
        String normalized = normalizeAccents(StringUtils.hasText(seed) ? seed : "HD");
        String compact = normalized.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (compact.length() > 8) {
            compact = compact.substring(0, 8);
        }
        return StringUtils.hasText(compact) ? compact : "HD";
    }

    private String normalizeAccents(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    private String normalizeCouncilReference(String value) {
        return normalizeAccents(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compactCouncilReference(String value) {
        return normalizeCouncilReference(value).replace(" ", "");
    }

    private record CouncilRef(String code, String name) {
    }
}
