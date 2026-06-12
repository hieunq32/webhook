package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.OpenAiProperties;
import com.example.recruitmentbot.config.OllamaProperties;
import com.example.recruitmentbot.config.RecruitmentMockProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RecruitmentReplyService {

    private static final Logger log = LoggerFactory.getLogger(RecruitmentReplyService.class);
    private static final Pattern EXPERIENCE_PATTERN = Pattern.compile("(\\d{1,2})\\s*(nam|year|years|yr|yrs)");
    private static final Pattern VIETNAMESE_CHAR_PATTERN = Pattern.compile("[\\p{IsLatin}&&[^\u0000-\u007F]]");
    private static final int MIN_EXP_YEARS = 0;
    private static final int MAX_EXP_YEARS = 40;
    private static final String ROLE_PROMPT_VI =
            "Ban cho em biet vi tri ban muon ung (VD: Java Developer, Golang Developer, Fullstack).";
    private static final String ROLE_PROMPT_EN =
            "Tell me the position you want to apply for (example: Java Developer, Golang Developer, Fullstack).";
    private static final String EXPERIENCE_PROMPT_VI =
            "Em da nhan vi tri. Ban cho em biet so nam kinh nghiem cua ban (VD: 2 nam).";
    private static final String EXPERIENCE_PROMPT_EN =
            "Great! Now send your years of experience (example: 2 years).";
    private static final String LOCATION_PROMPT_VI =
            "De em tra cuu JD chinh xac, cho em biet khu vuc ban yeu thich (VD: Hanoi, HCM, Da Nang).";
    private static final String LOCATION_PROMPT_EN =
            "To match the right JD, tell me your preferred location (e.g., Hanoi, HCM, Da Nang).";

    private final OpenAiProperties openAiProperties;
    private final OpenAiService openAiService;
    private final OllamaService ollamaService;
    private final OllamaProperties ollamaProperties;
    private final RecruitmentMockProperties mockProperties;
    private final Map<String, CandidateProfile> candidateProfiles = new ConcurrentHashMap<>();
    private final Map<String, String> jdDriveLinkByRoleAlias = new ConcurrentHashMap<>();
    private final Map<String, String> jdByRoleAlias = new ConcurrentHashMap<>();

    public RecruitmentReplyService(OpenAiProperties openAiProperties,
                                   OpenAiService openAiService,
                                   OllamaService ollamaService,
                                   OllamaProperties ollamaProperties,
                                   RecruitmentMockProperties mockProperties) {
        this.openAiProperties = openAiProperties;
        this.openAiService = openAiService;
        this.ollamaService = ollamaService;
        this.ollamaProperties = ollamaProperties;
        this.mockProperties = mockProperties;
        initPositionIndex();
    }

    public RecruitmentReply generateReply(String candidateMessage, String senderId) {
        if (openAiProperties.isOpenAiMode()) {
            return new RecruitmentReply(openAiService.generateRecruitmentReply(candidateMessage), null);
        }
        RecruitmentReply mockReply = buildMockReply(candidateMessage, senderId);
        if (openAiProperties.isOllamaMode() && shouldUseOllamaFallback(candidateMessage, mockReply)) {
            log.info("Routing candidate message to Ollama fallback. senderId={}, message={}", senderId, candidateMessage);
            return new RecruitmentReply(ollamaService.generateRecruitmentReply(buildOllamaPrompt(candidateMessage, senderId)), null);
        }
        return mockReply;
    }

    private String buildOllamaPrompt(String candidateMessage, String senderId) {
        CandidateProfile profile = StringUtils.hasText(senderId) ? candidateProfiles.get(senderId) : null;
        if (profile == null) {
            return candidateMessage;
        }

        StringBuilder prompt = new StringBuilder(candidateMessage);
        prompt.append("\n\nCandidate context:");
        if (StringUtils.hasText(profile.getRole())) {
            prompt.append("\n- role: ").append(profile.getRole());
        }
        if (StringUtils.hasText(profile.getExperienceYears())) {
            prompt.append("\n- experienceYears: ").append(profile.getExperienceYears());
        }
        if (StringUtils.hasText(profile.getLocation())) {
            prompt.append("\n- location: ").append(profile.getLocation());
        }
        prompt.append("\n- replyLanguage: ")
                .append(isVietnamese(candidateMessage) ? "Vietnamese" : "English");
        prompt.append("\n- scope: recruitment only");
        if (StringUtils.hasText(ollamaProperties.systemPrompt())) {
            prompt.append("\n- policy: follow configured recruitment assistant rules");
        }
        return prompt.toString();
    }

    private boolean shouldUseOllamaFallback(String candidateMessage, RecruitmentReply mockReply) {
        if (mockReply == null || mockReply.documentUrl() != null) {
            return false;
        }

        boolean vietnamese = isVietnamese(candidateMessage);
        String normalizedMessage = normalizeText(candidateMessage);
        String fallbackReply = vietnamese
                ? safe(mockProperties.vietnameseFallbackReply())
                : safe(mockProperties.englishFallbackReply());
        String outOfScopeReply = vietnamese
                ? safe(mockProperties.vietnameseReplyForOutOfScope())
                : safe(mockProperties.englishReplyForOutOfScope());
        String rolePrompt = vietnamese ? ROLE_PROMPT_VI : ROLE_PROMPT_EN;
        return fallbackReply.equals(mockReply.text())
                || outOfScopeReply.equals(mockReply.text())
                || (rolePrompt.equals(mockReply.text()) && !hasStructuredSignal(normalizedMessage));
    }

    private RecruitmentReply buildMockReply(String candidateMessage, String senderId) {
        String normalizedMessage = normalizeText(candidateMessage);
        boolean vietnamese = isVietnamese(candidateMessage);
        boolean outOfScope = containsAny(normalizedMessage, toArray(mockProperties.outOfScopeKeywords()));
        boolean recruitmentTopic = isRecruitmentTopic(normalizedMessage);
        boolean structuredSignal = hasStructuredSignal(normalizedMessage);

        String fallbackReply = vietnamese
                ? safe(mockProperties.vietnameseFallbackReply())
                : safe(mockProperties.englishFallbackReply());
        String outOfScopeReply = vietnamese
                ? safe(mockProperties.vietnameseReplyForOutOfScope())
                : safe(mockProperties.englishReplyForOutOfScope());

        if (containsAny(normalizedMessage, toArray(mockProperties.greetingKeywords()))) {
            return new RecruitmentReply(vietnamese
                            ? "Xin chao! Minh la bot tuyen dung. Ban hay cho em biet vi tri ban muon ung va kinh nghiem ban co bao nhieu nam."
                            : "Hi! I'm recruitment bot. Share your target position and years of experience.",
                    null);
        }

        if (!StringUtils.hasText(senderId)) {
            return new RecruitmentReply(fallbackReply, null);
        }

        String detectedRole = detectRole(normalizedMessage);
        String detectedExperience = detectExperience(normalizedMessage);
        String detectedLocation = detectLocation(normalizedMessage);

        if (containsAny(normalizedMessage, toArray(mockProperties.thanksKeywords()))) {
            return new RecruitmentReply(vietnamese
                            ? "Rat vui duoc giup ban. Ban co the hoi tiep ve vi tri, kinh nghiem, hoac luong."
                            : "You're welcome. You can continue asking about role, experience, or compensation.",
                    null);
        }

        if (outOfScope && detectedRole == null && !isRecruitmentTopic(normalizedMessage)) {
            return new RecruitmentReply(outOfScopeReply, null);
        }

        if (!recruitmentTopic && !structuredSignal) {
            return new RecruitmentReply(fallbackReply, null);
        }

        CandidateProfile profile = candidateProfiles.computeIfAbsent(senderId, key -> new CandidateProfile());

        if (detectedRole != null) {
            profile.setRole(detectedRole);
            profile.setDocumentSent(false);
        }
        if (detectedExperience != null) {
            profile.setExperienceYears(detectedExperience);
        }
        if (detectedLocation != null) {
            profile.setLocation(detectedLocation);
        }

        if (!StringUtils.hasText(profile.getRole())) {
            return new RecruitmentReply(vietnamese ? ROLE_PROMPT_VI : ROLE_PROMPT_EN, null);
        }

        if (!StringUtils.hasText(profile.getExperienceYears())) {
            return new RecruitmentReply(vietnamese ? EXPERIENCE_PROMPT_VI : EXPERIENCE_PROMPT_EN, null);
        }

        if (!StringUtils.hasText(profile.getLocation())) {
            return new RecruitmentReply(vietnamese ? LOCATION_PROMPT_VI : LOCATION_PROMPT_EN, null);
        }

        String roleAlias = normalizeText(profile.getRole());
        String driveLink = findDriveLink(roleAlias);
        String relativeDocPath = findJdDocument(roleAlias);
        if (!StringUtils.hasText(driveLink) && !StringUtils.hasText(relativeDocPath)) {
            return new RecruitmentReply(vietnamese
                            ? "Em da nhan du thong tin: " + profile.description(true)
                            + ". Hien tai chua co JD cho vi tri " + profile.getRole() + "."
                            : "I received all details: " + profile.description(false)
                            + ". I still don't have a JD for the position " + profile.getRole() + ".",
                    null);
        }

        if (profile.isDocumentSent()) {
            return new RecruitmentReply(vietnamese
                    ? "Toi da gui JD cho vi tri " + profile.getRole() + " roi. Ban muon toi ho tro gi tiep?"
                    : "I already sent the JD for " + profile.getRole() + ". What else can I help with?",
                    null);
        }

        if (StringUtils.hasText(driveLink)) {
            String completionReply = vietnamese
                    ? "Ban da hoan thanh thong tin: " + profile.description(true)
                    + ". Day la link Google Drive cua JD vi tri nay, ban co the mo xem hoac tai file: "
                    : "Your profile is complete: " + profile.description(false)
                    + ". Here is the Google Drive link for this position. You can open it or download the file: ";
            profile.setDocumentSent(true);
            return new RecruitmentReply(completionReply + driveLink, driveLink);
        }

        String publicBaseUrl = normalizeUrl(mockProperties.jdPublicBaseUrl());
        if (!StringUtils.hasText(publicBaseUrl)) {
            return new RecruitmentReply(vietnamese
                    ? "Profile cua ban: " + profile.description(true)
                    + ". Nhung chua co cau hinh mock.recruitment.jd-public-base-url de gui file JD Word."
                    : "Profile complete: " + profile.description(false)
                    + ". Configure mock.recruitment.jd-public-base-url to send Word file links.",
                    null);
        }

        String docUrl = publicBaseUrl + "/" + relativeDocPath.replace("\\", "/");
        String completionReply = vietnamese
                ? "Ban da hoan thanh thong tin: " + profile.description(true)
                + ". Em gui JD ban do tai: "
                : "Your profile is complete: " + profile.description(false)
                + ". Here is the JD for your position: ";
        profile.setDocumentSent(true);
        return new RecruitmentReply(completionReply + docUrl, docUrl);
    }

    private String detectRole(String normalizedMessage) {
        return findBestRoleAliasMatch(normalizedMessage);
    }

    private String detectExperience(String normalizedMessage) {
        Matcher matcher = EXPERIENCE_PATTERN.matcher(normalizedMessage);
        if (matcher.find()) {
            int years = Integer.parseInt(matcher.group(1));
            if (years >= MIN_EXP_YEARS && years <= MAX_EXP_YEARS) {
                return String.valueOf(years);
            }
        }
        return null;
    }

    private String detectLocation(String normalizedMessage) {
        if (containsAny(normalizedMessage, "hanoi", "ha noi", "ha-noi")) {
            return "Hanoi";
        }
        if (containsAny(normalizedMessage, "hcm", "hochiminh", "ho chi minh", "saigon", "sai gon")) {
            return "HCMC";
        }
        if (containsAny(normalizedMessage, "danang", "da nang")) {
            return "Da Nang";
        }
        if (containsAny(normalizedMessage, "cantho", "can tho", "c an tho", "can-tho", "hue", "quang nam")) {
            return "Other";
        }
        return null;
    }

    private String firstMatchingKeyword(String normalizedMessage, List<String> keywords) {
        if (keywords == null) {
            return null;
        }
        for (String keyword : keywords) {
            String normalized = normalizeText(keyword);
            if (normalized.length() > 0 && normalizedMessage.contains(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private boolean isRecruitmentTopic(String normalizedMessage) {
        return findBestRoleAliasMatch(normalizedMessage) != null
                || containsAny(normalizedMessage, toArray(mockProperties.roleKeywords()))
                || containsAny(normalizedMessage, toArray(mockProperties.cvKeywords()))
                || containsAny(normalizedMessage, toArray(mockProperties.interviewKeywords()))
                || containsAny(normalizedMessage, toArray(mockProperties.salaryKeywords()))
                || containsAny(normalizedMessage, toArray(mockProperties.contactKeywords()))
                || containsAny(normalizedMessage, toArray(mockProperties.statusKeywords()))
                || containsAny(normalizedMessage, "job", "jobs", "position", "positions", "apply", "resume", "candidate", "interview");
    }

    private boolean hasStructuredSignal(String normalizedMessage) {
        return StringUtils.hasText(detectRole(normalizedMessage))
                || StringUtils.hasText(detectExperience(normalizedMessage))
                || StringUtils.hasText(detectLocation(normalizedMessage));
    }

    private boolean containsAny(String message, String... keywords) {
        if (!StringUtils.hasText(message) || keywords == null || keywords.length == 0) {
            return false;
        }

        for (String keyword : keywords) {
            String normalizedKeyword = normalizeText(keyword);
            if (StringUtils.hasText(normalizedKeyword) && message.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private String[] toArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new String[0];
        }

        return values.stream().map(this::normalizeText).toArray(String[]::new);
    }

    private String normalizeUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean isVietnamese(String message) {
        if (!StringUtils.hasText(message)) {
            return true;
        }
        if (VIETNAMESE_CHAR_PATTERN.matcher(message).find()) {
            return true;
        }

        String normalized = normalizeText(message);
        int vietnameseSignals = countMatches(normalized,
                "xin chao", "chao", "vi tri", "ung tuyen", "tuyen dung", "kinh nghiem",
                "khu vuc", "lam viec", "ha noi", "da nang", "ho chi minh", "luong",
                "phuc loi", "cam on", "toi", "ban", "jd");
        int englishSignals = countMatches(normalized,
                "hello", "hi", "position", "apply", "application", "experience",
                "location", "work", "salary", "benefit", "thanks", "thank you",
                "candidate", "role", "job");

        return vietnameseSignals >= englishSignals;
    }

    private int countMatches(String normalizedMessage, String... keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && normalizedMessage.contains(normalizeText(keyword))) {
                count++;
            }
        }
        return count;
    }

    private void initPositionIndex() {
        initDriveLinks();

        String root = mockProperties.jdRootPath();
        if (!StringUtils.hasText(root)) {
            log.warn("mock.recruitment.jd-root-path is empty; JD by role matching is disabled.");
            return;
        }

        Path rootPath = Path.of(root);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.warn("JD root path does not exist or is not a directory: {}", root);
            return;
        }

        try (Stream<Path> folders = Files.list(rootPath)) {
            folders
                    .filter(Files::isDirectory)
                    .forEach(this::indexPositionFolder);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read JD root directory", exception);
        }
    }

    private void indexPositionFolder(Path folderPath) {
        String relativePath = buildRelativeDocPath(folderPath);
        String folderRoleAlias = normalizeRoleAlias(folderPath.getFileName().toString());
        String driveLink = findConfiguredDriveLink(folderRoleAlias);

        if (!StringUtils.hasText(relativePath) && !StringUtils.hasText(driveLink)) {
            return;
        }

        for (String alias : buildRoleAliases(folderRoleAlias)) {
            if (StringUtils.hasText(relativePath)) {
                addRoleAlias(alias, relativePath);
            }
            if (StringUtils.hasText(driveLink)) {
                addDriveLinkAlias(alias, driveLink);
            }
            if (StringUtils.hasText(alias)) {
                if (StringUtils.hasText(driveLink)) {
                    log.info("Registered Google Drive JD alias='{}' => {}", alias, driveLink);
                } else {
                    log.info("Registered local JD alias='{}' => {}", alias, relativePath);
                }
            }
        }
    }

    private void addRoleAlias(String alias, String path) {
        if (!StringUtils.hasText(alias) || !StringUtils.hasText(path)) {
            return;
        }
        jdByRoleAlias.put(alias, path);
    }

    private void addDriveLinkAlias(String alias, String link) {
        if (!StringUtils.hasText(alias) || !StringUtils.hasText(link)) {
            return;
        }
        jdDriveLinkByRoleAlias.put(alias, link);
    }

    private String normalizeRoleAlias(String folderName) {
        String text = folderName.replaceAll("([a-z])([A-Z])", "$1 $2");
        return normalizeText(text);
    }

    private String buildRelativeDocPath(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            Optional<Path> doc = files
                    .filter(file -> isWordDocument(file.getFileName().toString()))
                    .sorted((left, right) -> compareWordFilePriority(left.getFileName().toString(), right.getFileName().toString()))
                    .findFirst();
            if (doc.isEmpty()) {
                return null;
            }
            return folder.getFileName().toString() + "/" + doc.get().getFileName().toString();
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean isWordDocument(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".doc") || lower.endsWith(".docx");
    }

    private int compareWordFilePriority(String leftFileName, String rightFileName) {
        return Integer.compare(wordFilePriority(leftFileName), wordFilePriority(rightFileName));
    }

    private int wordFilePriority(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            return 0;
        }
        if (lower.endsWith(".docx")) {
            return 1;
        }
        return 99;
    }

    private List<String> buildRoleAliases(String roleAlias) {
        List<String> aliases = new ArrayList<>();
        if (!StringUtils.hasText(roleAlias)) {
            return aliases;
        }
        String normalized = normalizeText(roleAlias);
        aliases.add(normalized);
        aliases.add(normalized.replace(" ", ""));
        for (String token : normalized.split(" ")) {
            if (StringUtils.hasText(token)) {
                aliases.add(token);
            }
        }
        aliases.addAll(buildCommonRoleVariants(normalized));
        return aliases;
    }

    private List<String> buildCommonRoleVariants(String normalizedRole) {
        List<String> aliases = new ArrayList<>();

        if (normalizedRole.contains("java")) {
            aliases.add("java dev");
            aliases.add("backend java");
        }
        if (normalizedRole.contains("golang") || normalizedRole.contains("go lang")) {
            aliases.add("golang");
            aliases.add("go developer");
            aliases.add("go dev");
            aliases.add("backend go");
        }
        if (normalizedRole.contains("fullstack") || normalizedRole.contains("full stack")) {
            aliases.add("full stack");
            aliases.add("full stack developer");
            aliases.add("fullstack developer");
            aliases.add("fs");
        }

        return aliases;
    }

    private void initDriveLinks() {
        Map<String, String> configuredLinks = mockProperties.jdDriveLinks();
        if (configuredLinks == null || configuredLinks.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : configuredLinks.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }

            for (String alias : buildRoleAliases(normalizeRoleAlias(entry.getKey()))) {
                addDriveLinkAlias(alias, entry.getValue().trim());
            }
        }
    }

    private String findJdDocument(String roleAlias) {
        if (!StringUtils.hasText(roleAlias)) {
            return null;
        }
        String normalizedRole = normalizeText(roleAlias);
        String direct = jdByRoleAlias.get(normalizedRole);
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        String compact = normalizedRole.replace(" ", "");
        direct = jdByRoleAlias.get(compact);
        if (StringUtils.hasText(direct)) {
            return direct;
        }

        for (Map.Entry<String, String> entry : jdByRoleAlias.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            if (normalizedRole.contains(entry.getKey()) || entry.getKey().contains(normalizedRole)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String findDriveLink(String roleAlias) {
        if (!StringUtils.hasText(roleAlias)) {
            return null;
        }
        String normalizedRole = normalizeText(roleAlias);
        String direct = jdDriveLinkByRoleAlias.get(normalizedRole);
        if (StringUtils.hasText(direct)) {
            return direct;
        }

        String compact = normalizedRole.replace(" ", "");
        direct = jdDriveLinkByRoleAlias.get(compact);
        if (StringUtils.hasText(direct)) {
            return direct;
        }

        for (Map.Entry<String, String> entry : jdDriveLinkByRoleAlias.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            if (normalizedRole.contains(entry.getKey()) || entry.getKey().contains(normalizedRole)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String findConfiguredDriveLink(String roleAlias) {
        Map<String, String> configuredLinks = mockProperties.jdDriveLinks();
        if (configuredLinks == null || configuredLinks.isEmpty()) {
            return null;
        }

        String normalizedRole = normalizeText(roleAlias);
        for (Map.Entry<String, String> entry : configuredLinks.entrySet()) {
            String normalizedKey = normalizeRoleAlias(entry.getKey());
            if (normalizedRole.equals(normalizedKey)
                    || normalizedRole.contains(normalizedKey)
                    || normalizedKey.contains(normalizedRole)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String findBestRoleAliasMatch(String normalizedMessage) {
        String bestMatch = null;

        for (String alias : jdByRoleAlias.keySet()) {
            bestMatch = chooseBetterAliasMatch(normalizedMessage, bestMatch, alias);
        }
        for (String alias : jdDriveLinkByRoleAlias.keySet()) {
            bestMatch = chooseBetterAliasMatch(normalizedMessage, bestMatch, alias);
        }

        return bestMatch;
    }

    private String chooseBetterAliasMatch(String normalizedMessage, String currentBest, String candidateAlias) {
        if (!StringUtils.hasText(candidateAlias) || !StringUtils.hasText(normalizedMessage)) {
            return currentBest;
        }
        if (!normalizedMessage.contains(candidateAlias)) {
            return currentBest;
        }
        if (currentBest == null || candidateAlias.length() > currentBest.length()) {
            return candidateAlias;
        }
        return currentBest;
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text : "";
    }

    public static final class CandidateProfile {
        private String role;
        private String experienceYears;
        private String location;
        private boolean documentSent;

        String getRole() {
            return role;
        }

        void setRole(String role) {
            this.role = role;
        }

        String getExperienceYears() {
            return experienceYears;
        }

        void setExperienceYears(String experienceYears) {
            this.experienceYears = experienceYears;
        }

        String getLocation() {
            return location;
        }

        void setLocation(String location) {
            this.location = location;
        }

        boolean isDocumentSent() {
            return documentSent;
        }

        void setDocumentSent(boolean documentSent) {
            this.documentSent = documentSent;
        }

        String description(boolean vietnamese) {
            if (vietnamese) {
                return "vi tri " + role + ", kinh nghiem " + experienceYears + " nam, khu vuc " + location;
            }
            return "role " + role + ", experience " + experienceYears + ", location " + location;
        }
    }

    public record RecruitmentReply(String text, String documentUrl) {
    }
}
