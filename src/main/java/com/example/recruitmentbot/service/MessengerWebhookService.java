package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.FacebookHrProperties;
import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import com.example.recruitmentbot.hradmin.domain.PageAdminPermission;
import com.example.recruitmentbot.hradmin.service.PageAdminAccountService;
import com.example.recruitmentbot.interview.service.CandidateProfileService;
import com.example.recruitmentbot.interview.service.InterviewSchedulingService;
import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.service.FacebookJobPostingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MessengerWebhookService {

    private static final Logger log = LoggerFactory.getLogger(MessengerWebhookService.class);
    private static final String OPENAI_UNAVAILABLE_MESSAGE =
            "Thanks for your message. I'm the recruitment assistant for this page, but I'm temporarily unavailable. Please try again shortly.";
    private static final long MESSAGE_DEDUP_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long ADMIN_SESSION_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int ADMIN_JOB_LIST_LIMIT = 8;
    private static final Pattern MENU_PREFIX_PATTERN = Pattern.compile("^\\s*([1-3])[\\s\\.:,-]*(.*)$");
    private static final Pattern JOB_ID_PREFIX_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*(.*)$");

    private final RecruitmentReplyService recruitmentReplyService;
    private final FacebookMessengerService facebookMessengerService;
    private final FacebookHrProperties facebookHrProperties;
    private final PageAdminAccountService pageAdminAccountService;
    private final CandidateProfileService candidateProfileService;
    private final FacebookJobPostingService facebookJobPostingService;
    private final InterviewSchedulingService interviewSchedulingService;
    private final ExecutorService webhookExecutor = Executors.newCachedThreadPool();
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private final Map<String, AdminConversationState> adminConversationStates = new ConcurrentHashMap<>();

    public MessengerWebhookService(
            RecruitmentReplyService recruitmentReplyService,
            FacebookMessengerService facebookMessengerService,
            FacebookHrProperties facebookHrProperties,
            PageAdminAccountService pageAdminAccountService,
            CandidateProfileService candidateProfileService,
            FacebookJobPostingService facebookJobPostingService,
            InterviewSchedulingService interviewSchedulingService
    ) {
        this.recruitmentReplyService = recruitmentReplyService;
        this.facebookMessengerService = facebookMessengerService;
        this.facebookHrProperties = facebookHrProperties;
        this.pageAdminAccountService = pageAdminAccountService;
        this.candidateProfileService = candidateProfileService;
        this.facebookJobPostingService = facebookJobPostingService;
        this.interviewSchedulingService = interviewSchedulingService;
    }

    public void processIncomingWebhook(JsonNode payload) {
        if (isDirectMessagingEvent(payload)) {
            dispatchMessagingEvent(payload);
            return;
        }

        if (!"page".equals(payload.path("object").asText())) {
            log.warn("Ignoring unsupported Facebook webhook object: {}", payload.path("object").asText());
            return;
        }

        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode messagingEvent : entry.path("messaging")) {
                dispatchMessagingEvent(messagingEvent);
            }
        }
    }

    private boolean isDirectMessagingEvent(JsonNode payload) {
        return payload.has("sender") && payload.has("message");
    }

    private void dispatchMessagingEvent(JsonNode messagingEvent) {
        webhookExecutor.submit(() -> processMessagingEvent(messagingEvent));
    }

    private void processMessagingEvent(JsonNode messagingEvent) {
        try {
            JsonNode messageNode = messagingEvent.path("message");
            if (messageNode.isMissingNode() || messageNode.isNull()) {
                return;
            }

            if (messageNode.path("is_echo").asBoolean(false)) {
                log.info("Ignoring echo message event");
                return;
            }

            String messageId = messageNode.path("mid").asText(null);
            String senderId = messagingEvent.path("sender").path("id").asText(null);
            String messageText = messageNode.path("text").asText(null);

            if (!StringUtils.hasText(senderId)) {
                log.warn("Skipping message event because sender id is missing: {}", messagingEvent.toPrettyString());
                return;
            }

            if (isDuplicateMessage(messageId)) {
                log.info("Skipping duplicate Messenger event. senderId={}, messageId={}", senderId, messageId);
                return;
            }

            if (!StringUtils.hasText(messageText)) {
                log.info("Ignoring non-text message from senderId={}", senderId);
                return;
            }

            if (interviewSchedulingService.handleHrReplyIfApplicable(senderId, messageText)) {
                return;
            }

            Optional<PageAdminAccount> adminAccount = resolveAdminAccount(senderId);
            if (adminAccount.isPresent()) {
                handleAdminConversation(adminAccount.get(), senderId, messageText);
                return;
            }

            candidateProfileService.ensureProfileExistsForMessengerSender(senderId);

            if (interviewSchedulingService.handleCandidateReplyIfApplicable(senderId, messageText)) {
                return;
            }

            if (interviewSchedulingService.autoStartSchedulingForPassedCandidateIfNeeded(senderId)) {
                return;
            }

            handleCandidateConversation(senderId, messageText);
        } catch (Exception exception) {
            log.error("Failed to process Messenger event:\n{}", messagingEvent.toPrettyString(), exception);
        }
    }

    private Optional<PageAdminAccount> resolveAdminAccount(String senderId) {
        if (!facebookHrProperties.enabled()) {
            return Optional.empty();
        }
        Optional<PageAdminAccount> adminAccount = pageAdminAccountService.findActiveBySenderId(senderId);
        adminAccount.ifPresent(account -> log.info(
                "Resolved page admin senderId={} role={} permissions={}",
                senderId,
                account.getRole(),
                account.getPermissions()
        ));
        return adminAccount;
    }

    private void handleCandidateConversation(String senderId, String messageText) {
        log.info("Received candidate text message from senderId={}: {}", senderId, messageText);

        String replyText;
        String documentUrl = null;
        try {
            RecruitmentReplyService.RecruitmentReply recruitmentReply =
                    recruitmentReplyService.generateReply(messageText, senderId);
            replyText = recruitmentReply.text();
            documentUrl = recruitmentReply.documentUrl();
        } catch (Exception exception) {
            log.error("Failed to generate recruitment reply for senderId={}", senderId, exception);
            replyText = OPENAI_UNAVAILABLE_MESSAGE;
        }

        try {
            facebookMessengerService.sendTextMessage(senderId, replyText);
        } catch (Exception exception) {
            log.error("Failed to send text reply to senderId={}. replyText={}", senderId, replyText, exception);
            return;
        }

        if (documentUrl != null) {
            try {
                facebookMessengerService.sendDocumentMessage(senderId, documentUrl);
            } catch (Exception exception) {
                log.error("Failed to send recruitment document to senderId={}. documentUrl={}",
                        senderId, documentUrl, exception);
            }
        }
    }

    private void handleAdminConversation(PageAdminAccount adminAccount, String senderId, String messageText) {
        log.info("Received admin text message from senderId={} role={}: {}", senderId, adminAccount.getRole(), messageText);

        cleanupAdminConversations();
        AdminConversationState state = adminConversationStates.computeIfAbsent(senderId, key -> new AdminConversationState());
        state.touch();

        String sanitizedText = messageText.trim();
        AdminMenuChoice menuChoice = parseMenuChoice(sanitizedText);
        String remainingText = menuChoice == null ? sanitizedText : menuChoice.remainder();

        if (state.mode == AdminConversationMode.IDLE) {
            if (menuChoice != null) {
                processAdminMenuChoice(adminAccount, senderId, state, menuChoice.option(), remainingText);
            } else {
                sendAdminMenu(adminAccount, senderId, null);
            }
            return;
        }

        switch (state.mode) {
            case AWAIT_JOB_DESCRIPTION -> processJobCreationInput(adminAccount, senderId, state, sanitizedText);
            case AWAIT_EDIT_JOB_ID -> processEditJobSelection(adminAccount, senderId, state, sanitizedText);
            case AWAIT_JOB_UPDATE_CONTENT -> processJobUpdateContent(adminAccount, senderId, state, sanitizedText);
            default -> {
                state.mode = AdminConversationMode.IDLE;
                state.targetJobId = null;
                sendAdminMenu(adminAccount, senderId, "Da quay ve menu chinh.");
            }
        }
    }

    private void processAdminMenuChoice(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            int option,
            String remainder
    ) {
        switch (option) {
            case 1 -> {
                if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
                    sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen dang bai tu dong.");
                    return;
                }
                state.mode = AdminConversationMode.AWAIT_JOB_DESCRIPTION;
                if (StringUtils.hasText(remainder)) {
                    processJobCreationInput(adminAccount, senderId, state, remainder);
                } else {
                    facebookMessengerService.sendTextMessage(senderId, buildCreatePrompt());
                }
            }
            case 2 -> {
                if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.EDIT_JOB_POST)) {
                    sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen sua bai dang.");
                    return;
                }
                state.mode = AdminConversationMode.AWAIT_EDIT_JOB_ID;
                state.targetJobId = null;
                if (StringUtils.hasText(remainder)) {
                    processEditJobSelection(adminAccount, senderId, state, remainder);
                } else {
                    facebookMessengerService.sendTextMessage(senderId,
                            "Gui theo format: <jobId> <noi dung moi>, hoac chi gui <jobId> de nhap noi dung o buoc sau.");
                }
            }
            case 3 -> {
                if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.VIEW_HR_SCHEDULE)) {
                    sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen xem lich.");
                    return;
                }
                state.mode = AdminConversationMode.IDLE;
                sendJobScheduleSummary(adminAccount, senderId);
            }
            default -> sendAdminMenu(adminAccount, senderId, "Lua chon khong hop le.");
        }
    }

    private void processJobCreationInput(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            sendAdminMenu(adminAccount, senderId, "Da huy thao tac.");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService.createAndPublishFromText(text);
        state.mode = AdminConversationMode.IDLE;
        sendAdminFlowResponse(adminAccount, senderId, response);
    }

    private void processEditJobSelection(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        AdminJobEditRequest editRequest = parseEditRequest(text);
        if (editRequest.jobId == null) {
            facebookMessengerService.sendTextMessage(senderId,
                    "Khong xac dinh duoc jobId. Hay gui theo format: <jobId> <noi dung moi>.");
            return;
        }

        if (!StringUtils.hasText(editRequest.content)) {
            state.mode = AdminConversationMode.AWAIT_JOB_UPDATE_CONTENT;
            state.targetJobId = editRequest.jobId;
            facebookMessengerService.sendTextMessage(senderId,
                    "Da nhan jobId=" + editRequest.jobId + ". Gui noi dung moi de thay the va dang lai.");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService
                .updateFromTextAndRepublish(editRequest.jobId, editRequest.content);
        state.mode = AdminConversationMode.IDLE;
        sendAdminFlowResponse(adminAccount, senderId, response);
    }

    private void processJobUpdateContent(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if (state.targetJobId == null) {
            state.mode = AdminConversationMode.IDLE;
            sendAdminMenu(adminAccount, senderId, "Phien sua bai khong hop le. Hay chon lai tu menu.");
            return;
        }

        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            state.targetJobId = null;
            sendAdminMenu(adminAccount, senderId, "Da huy sua bai.");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService
                .updateFromTextAndRepublish(state.targetJobId, text);
        state.mode = AdminConversationMode.IDLE;
        state.targetJobId = null;
        sendAdminFlowResponse(adminAccount, senderId, response);
    }

    private void sendAdminFlowResponse(PageAdminAccount adminAccount, String senderId, FacebookPostOperationResponse response) {
        StringBuilder reply = new StringBuilder();
        if (response.success()) {
            reply.append("Hoan tat: ").append(response.message()).append('\n');
            reply.append("JobId: ").append(response.jobDescriptionId()).append('\n');
            if (StringUtils.hasText(response.facebookPostId())) {
                reply.append("Facebook Post ID: ").append(response.facebookPostId()).append('\n');
            }
            if (StringUtils.hasText(response.generatedContent())) {
                reply.append("Noi dung dang:\n");
                String truncated = truncateText(response.generatedContent(), 900);
                reply.append(truncated);
                if (response.generatedContent().length() > 900) {
                    reply.append(" ...");
                }
            }
        } else {
            reply.append("Khong the hoan tat: ").append(response.message());
        }
        facebookMessengerService.sendTextMessage(senderId, reply.toString());
        sendAdminMenu(adminAccount, senderId, "Quay ve menu.");
    }

    private void sendJobScheduleSummary(PageAdminAccount adminAccount, String senderId) {
        facebookMessengerService.sendTextMessage(senderId, interviewSchedulingService.buildUpcomingVOfficeScheduleSummary());
        sendAdminMenu(adminAccount, senderId, null);
    }

    private void sendAdminMenu(PageAdminAccount adminAccount, String senderId, String prefix) {
        StringBuilder menu = new StringBuilder();
        if (StringUtils.hasText(prefix)) {
            menu.append(prefix).append('\n');
        }

        int optionCount = 0;
        String displayName = StringUtils.hasText(adminAccount.getDisplayName())
                ? adminAccount.getDisplayName()
                : "HR";
        menu.append("Menu HR - Xin chào ").append(displayName).append(":\n");

        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
            menu.append("1. Dang bai tu dong\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.EDIT_JOB_POST)) {
            menu.append("2. Sua bai dang\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.VIEW_HR_SCHEDULE)) {
            menu.append("3. Xem lich\n");
            optionCount++;
        }

        if (optionCount == 0) {
            menu.append("Tai khoan admin nay chua duoc cap quyen trong database.");
        } else {
            menu.append("Nhap 1/2/3 hoac go ten chuc nang.");
        }

        facebookMessengerService.sendTextMessage(senderId, menu.toString());
    }

    private AdminMenuChoice parseMenuChoice(String messageText) {
        Matcher prefixMatcher = MENU_PREFIX_PATTERN.matcher(messageText);
        if (prefixMatcher.matches()) {
            int option = Integer.parseInt(prefixMatcher.group(1));
            String remaining = prefixMatcher.group(2) == null ? "" : prefixMatcher.group(2).trim();
            return new AdminMenuChoice(option, remaining);
        }

        String normalized = normalizeText(messageText);
        if (normalized.contains("dang bai")) {
            return new AdminMenuChoice(1, messageText);
        }
        if (normalized.contains("sua bai")) {
            return new AdminMenuChoice(2, messageText);
        }
        if (normalized.contains("xem lich")) {
            return new AdminMenuChoice(3, "");
        }
        return null;
    }

    private AdminJobEditRequest parseEditRequest(String text) {
        if (!StringUtils.hasText(text)) {
            return new AdminJobEditRequest(null, null);
        }
        Matcher matcher = JOB_ID_PREFIX_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return new AdminJobEditRequest(null, null);
        }

        Long jobId = Long.parseLong(matcher.group(1));
        String content = matcher.group(2);
        return new AdminJobEditRequest(jobId, StringUtils.hasText(content) ? content.trim() : null);
    }

    private void cleanupAdminConversations() {
        long now = System.currentTimeMillis();
        adminConversationStates.entrySet().removeIf(entry -> now - entry.getValue().lastActiveAt > ADMIN_SESSION_TTL_MILLIS);
    }

    private String buildCreatePrompt() {
        return "Gui mo ta job tu do: vi tri, mo ta cong viec, ky nang, luong, dia diem, hinh thuc lam viec. "
                + "Vi du: Java Developer - 2 nam exp - Spring Boot, SQL - luong 25-40tr - Ha Noi - hybrid.";
    }

    private String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private String normalizeText(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    private boolean isDuplicateMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }

        long now = System.currentTimeMillis();
        processedMessageIds.entrySet().removeIf(entry -> now - entry.getValue() > MESSAGE_DEDUP_TTL_MILLIS);
        return processedMessageIds.putIfAbsent(messageId, now) != null;
    }

    private enum AdminConversationMode {
        IDLE,
        AWAIT_JOB_DESCRIPTION,
        AWAIT_EDIT_JOB_ID,
        AWAIT_JOB_UPDATE_CONTENT
    }

    private static class AdminConversationState {
        private AdminConversationMode mode = AdminConversationMode.IDLE;
        private Long targetJobId;
        private long lastActiveAt = System.currentTimeMillis();

        void touch() {
            lastActiveAt = System.currentTimeMillis();
        }
    }

    private record AdminMenuChoice(int option, String remainder) {
    }

    private record AdminJobEditRequest(Long jobId, String content) {
    }
}
