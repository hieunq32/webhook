package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.FacebookHrProperties;
import com.example.recruitmentbot.council.domain.CouncilHiringRequest;
import com.example.recruitmentbot.council.service.RecruitmentCouncilService;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetRequest;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetResponse;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupPostingService;
import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import com.example.recruitmentbot.hradmin.domain.PageAdminPermission;
import com.example.recruitmentbot.hradmin.domain.PageAdminRole;
import com.example.recruitmentbot.hradmin.service.PageAdminAccountService;
import com.example.recruitmentbot.interview.service.CandidateProfileService;
import com.example.recruitmentbot.interview.service.InterviewSchedulingService;
import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.service.FacebookJobPostingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.Arrays;
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
    private static final Pattern MENU_PREFIX_PATTERN = Pattern.compile("^\\s*([1-5])[\\s\\.:,-]*(.*)$");
    private static final Pattern SUBMENU_PREFIX_PATTERN = Pattern.compile("^\\s*([1-2])[\\s\\.:,-]*(.*)$");
    private static final Pattern JOB_ID_PREFIX_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*(.*)$");

    private final RecruitmentReplyService recruitmentReplyService;
    private final FacebookMessengerService facebookMessengerService;
    private final FacebookHrProperties facebookHrProperties;
    private final PageAdminAccountService pageAdminAccountService;
    private final CandidateProfileService candidateProfileService;
    private final FacebookJobPostingService facebookJobPostingService;
    private final FacebookGroupPostingService facebookGroupPostingService;
    private final InterviewSchedulingService interviewSchedulingService;
    private final RecruitmentCouncilService recruitmentCouncilService;
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
            FacebookGroupPostingService facebookGroupPostingService,
            InterviewSchedulingService interviewSchedulingService,
            RecruitmentCouncilService recruitmentCouncilService
    ) {
        this.recruitmentReplyService = recruitmentReplyService;
        this.facebookMessengerService = facebookMessengerService;
        this.facebookHrProperties = facebookHrProperties;
        this.pageAdminAccountService = pageAdminAccountService;
        this.candidateProfileService = candidateProfileService;
        this.facebookJobPostingService = facebookJobPostingService;
        this.facebookGroupPostingService = facebookGroupPostingService;
        this.interviewSchedulingService = interviewSchedulingService;
        this.recruitmentCouncilService = recruitmentCouncilService;
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
                handleNonTextMessage(senderId, messageNode);
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

            try {
                if (interviewSchedulingService.handleCandidateReplyIfApplicable(senderId, messageText)) {
                    return;
                }

                if (interviewSchedulingService.autoStartSchedulingForPassedCandidateIfNeeded(senderId)) {
                    return;
                }
            } catch (Exception exception) {
                log.error("Interview scheduling flow failed for senderId={}. Falling back to recruitment chat.", senderId, exception);
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

    private void handleNonTextMessage(String senderId, JsonNode messageNode) {
        if (hasImageAttachment(messageNode)) {
            log.info("Received image attachment from senderId={}. Asking candidate for JD code.", senderId);
            facebookMessengerService.sendTextMessage(senderId,
                    "Minh da nhan anh ban gui. Hien tai bot chua scan noi dung trong anh.\n"
                            + "De minh tu van dung vi tri, ban vui long gui ma JD tren bai dang, vi du: JD-6.\n"
                            + "Neu khong thay ma JD, ban co the gui ten vi tri, vi du: Java Developer, Golang Developer.");
            return;
        }

        log.info("Ignoring unsupported non-text message from senderId={}", senderId);
    }

    private boolean hasImageAttachment(JsonNode messageNode) {
        JsonNode attachments = messageNode.path("attachments");
        if (!attachments.isArray()) {
            return false;
        }

        for (JsonNode attachment : attachments) {
            if ("image".equalsIgnoreCase(attachment.path("type").asText())) {
                return true;
            }
        }
        return false;
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
            log.info("Generated recruitment reply for senderId={}, textLength={}, hasDocument={}",
                    senderId,
                    replyText == null ? 0 : replyText.length(),
                    documentUrl != null);
        } catch (Exception exception) {
            log.error("Failed to generate recruitment reply for senderId={}", senderId, exception);
            replyText = OPENAI_UNAVAILABLE_MESSAGE;
        }

        if (!StringUtils.hasText(replyText)) {
            log.warn("Generated empty recruitment reply for senderId={}. Falling back to unavailable message.", senderId);
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
        if (interviewSchedulingService.handleHrReplyIfApplicable(senderId, sanitizedText)) {
            state.mode = AdminConversationMode.IDLE;
            state.targetJobId = null;
            state.targetSlotId = null;
            return;
        }

        if (adminAccount.getRole() == PageAdminRole.COUNCIL) {
            handleCouncilConversation(adminAccount, senderId, state, sanitizedText);
            return;
        }

        if (handleGroupManagementIntent(adminAccount, senderId, sanitizedText)) {
            state.mode = AdminConversationMode.IDLE;
            return;
        }

        if (isGroupPostIntent(sanitizedText)) {
            state.mode = AdminConversationMode.IDLE;
            processGroupPostIntent(adminAccount, senderId, state, sanitizedText);
            return;
        }

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

        AdminMenuChoice intentMenuChoice = parseIntentMenuChoice(sanitizedText);
        if (intentMenuChoice != null) {
            state.mode = AdminConversationMode.IDLE;
            state.targetJobId = null;
            state.targetSlotId = null;
            processAdminMenuChoice(adminAccount, senderId, state, intentMenuChoice.option(), intentMenuChoice.remainder());
            return;
        }

        switch (state.mode) {
            case AWAIT_JOB_DESCRIPTION -> processJobCreationInput(adminAccount, senderId, state, sanitizedText);
            case AWAIT_EDIT_JOB_ID -> processEditJobSelection(adminAccount, senderId, state, sanitizedText);
            case AWAIT_JOB_UPDATE_CONTENT -> processJobUpdateContent(adminAccount, senderId, state, sanitizedText);
            case SCHEDULE_MENU -> processScheduleMenuInput(adminAccount, senderId, state, sanitizedText);
            case AWAIT_SCHEDULE_DETAIL_SLOT_ID -> processScheduleDetailRequest(adminAccount, senderId, state, sanitizedText);
            case AWAIT_SCHEDULE_EDIT_SLOT_ID -> processScheduleEditSelection(adminAccount, senderId, state, sanitizedText);
            case AWAIT_SCHEDULE_EDIT_REASON -> processScheduleEditReason(adminAccount, senderId, state, sanitizedText);
            case AWAIT_COUNCIL_HIRING_REQUEST -> processCouncilHiringRequest(adminAccount, senderId, state, sanitizedText);
            case AWAIT_GROUP_POST_JOB_SELECTION -> processGroupPostJobSelection(adminAccount, senderId, state, sanitizedText);
            default -> {
                state.mode = AdminConversationMode.IDLE;
                state.targetJobId = null;
                state.targetSlotId = null;
                sendAdminMenu(adminAccount, senderId, "Da quay ve menu chinh.");
            }
        }
    }

    private boolean handleGroupManagementIntent(PageAdminAccount adminAccount, String senderId, String text) {
        String normalized = normalizeText(text);
        if (matchesIntent(normalized, "them group")
                || matchesIntent(normalized, "them nhom")
                || matchesIntent(normalized, "add group")) {
            if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
                sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen quan ly Facebook Group.");
                return true;
            }
            String payload = stripLeadingWords(text, normalized.startsWith("add group") ? 2 : 2);
            FacebookGroupTargetRequest request = parseGroupTargetRequest(payload);
            if (request == null) {
                facebookMessengerService.sendTextMessage(senderId,
                        "Gui theo format: them group <ten group> | <group id hoac url>. Vi du: them group Java Jobs Ha Noi | https://facebook.com/groups/...");
                return true;
            }
            FacebookGroupTargetResponse response = facebookGroupPostingService.createGroup(request);
            facebookMessengerService.sendTextMessage(senderId,
                    "Da them Facebook Group:\n"
                            + "ID: " + response.id() + "\n"
                            + "Ten: " + response.displayName() + "\n"
                            + "Reference: " + response.groupReference());
            return true;
        }

        if (matchesIntent(normalized, "xem group")
                || matchesIntent(normalized, "xem nhom")
                || matchesIntent(normalized, "danh sach group")
                || matchesIntent(normalized, "danh sach nhom")
                || matchesIntent(normalized, "list group")
                || matchesIntent(normalized, "list groups")) {
            if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
                sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen quan ly Facebook Group.");
                return true;
            }
            facebookMessengerService.sendTextMessage(senderId, facebookGroupPostingService.buildGroupSelectionSummary());
            return true;
        }

        if (matchesIntent(normalized, "xoa group")
                || matchesIntent(normalized, "bo group")
                || matchesIntent(normalized, "tat group")
                || matchesIntent(normalized, "xoa nhom")
                || matchesIntent(normalized, "bo nhom")
                || matchesIntent(normalized, "tat nhom")) {
            if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
                sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen quan ly Facebook Group.");
                return true;
            }
            Long groupId = parseAnyLong(text);
            if (groupId == null) {
                facebookMessengerService.sendTextMessage(senderId, "Hay gui groupId can tat. Vi du: tat group 2");
                return true;
            }
            FacebookGroupTargetResponse response = facebookGroupPostingService.deactivateGroup(groupId);
            facebookMessengerService.sendTextMessage(senderId,
                    "Da tat Facebook Group:\n"
                            + "ID: " + response.id() + "\n"
                            + "Ten: " + response.displayName());
            return true;
        }

        return false;
    }

    private void processGroupPostIntent(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
            sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen dang bai group.");
            return;
        }

        GroupPostSelection selection = parseGroupPostSelection(text);
        if (selection.jobId() != null && selection.groupIds() != null && !selection.groupIds().isEmpty()) {
            publishJobToSelectedGroupsAndNotify(adminAccount, senderId, selection.jobId(), selection.groupIds());
            state.mode = AdminConversationMode.IDLE;
            return;
        }

        Optional<Long> jobId = facebookGroupPostingService.resolveOpenJobIdFromMessage(text);
        if (jobId.isEmpty()) {
            state.mode = AdminConversationMode.AWAIT_GROUP_POST_JOB_SELECTION;
            facebookMessengerService.sendTextMessage(senderId, facebookGroupPostingService.buildOpenJobSelectionPrompt());
            return;
        }

        publishJobToGroupsAndNotify(adminAccount, senderId, jobId.get());
        state.mode = AdminConversationMode.IDLE;
    }

    private void publishJobToSelectedGroupsAndNotify(
            PageAdminAccount adminAccount,
            String senderId,
            Long jobId,
            List<Long> groupIds
    ) {
        try {
            FacebookGroupPostSummaryResponse summary = facebookGroupPostingService.publishJobToSelectedGroups(
                    jobId,
                    groupIds,
                    senderId,
                    "messenger"
            );
            facebookMessengerService.sendTextMessage(senderId, facebookGroupPostingService.buildMessengerSummary(summary));
            sendAdminMenu(adminAccount, senderId, "Quay ve menu.");
        } catch (Exception exception) {
            log.error("Selected Facebook Group posting flow failed for HR senderId={}, jobId={}, groupIds={}",
                    senderId, jobId, groupIds, exception);
            sendAdminMenu(adminAccount, senderId, "Dang Facebook Group that bai: " + exception.getMessage());
        }
    }

    private void processGroupPostJobSelection(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            sendAdminMenu(adminAccount, senderId, "Da huy dang Facebook Group.");
            return;
        }

        Optional<Long> jobId = facebookGroupPostingService.resolveOpenJobIdFromMessage(text);
        if (jobId.isEmpty()) {
            facebookMessengerService.sendTextMessage(senderId,
                    "Chua xac dinh duoc JD. Hay tra loi bang jobId trong danh sach.\n"
                            + facebookGroupPostingService.buildOpenJobSelectionPrompt());
            return;
        }

        publishJobToGroupsAndNotify(adminAccount, senderId, jobId.get());
        state.mode = AdminConversationMode.IDLE;
    }

    private void publishJobToGroupsAndNotify(PageAdminAccount adminAccount, String senderId, Long jobId) {
        try {
            FacebookGroupPostSummaryResponse summary = facebookGroupPostingService.publishJobToActiveGroups(
                    jobId,
                    senderId,
                    "messenger"
            );
            facebookMessengerService.sendTextMessage(senderId, facebookGroupPostingService.buildMessengerSummary(summary));
            sendAdminMenu(adminAccount, senderId, "Quay ve menu.");
        } catch (Exception exception) {
            log.error("Facebook Group posting flow failed for HR senderId={}, jobId={}", senderId, jobId, exception);
            sendAdminMenu(adminAccount, senderId, "Dang Facebook Group that bai: " + exception.getMessage());
        }
    }

    private FacebookGroupTargetRequest parseGroupTargetRequest(String payload) {
        if (!StringUtils.hasText(payload) || !payload.contains("|")) {
            return null;
        }
        String[] parts = payload.split("\\|", 2);
        String displayName = parts[0].trim();
        String reference = parts[1].trim();
        if (!StringUtils.hasText(displayName) || !StringUtils.hasText(reference)) {
            return null;
        }
        return new FacebookGroupTargetRequest(displayName, reference, true, 100);
    }

    private boolean isGroupPostIntent(String text) {
        String normalized = normalizeText(text);
        String compact = compactText(normalized);
        return matchesIntent(normalized, "dang group")
                || matchesIntent(normalized, "post group")
                || matchesIntent(normalized, "dang nhom")
                || matchesIntent(normalized, "post nhom")
                || matchesIntent(normalized, "dang trong group")
                || matchesIntent(normalized, "dang trong nhom")
                || matchesIntent(normalized, "dang len group")
                || matchesIntent(normalized, "dang len nhom")
                || matchesIntent(normalized, "dang vao group")
                || matchesIntent(normalized, "dang vao nhom")
                || matchesIntent(normalized, "dang bai trong group")
                || matchesIntent(normalized, "dang bai trong nhom")
                || matchesIntent(normalized, "dang bai len group")
                || matchesIntent(normalized, "dang bai len nhom")
                || matchesIntent(normalized, "dang bai vao group")
                || matchesIntent(normalized, "dang bai vao nhom")
                || matchesIntent(normalized, "dang tin trong group")
                || matchesIntent(normalized, "dang tin trong nhom")
                || matchesIntent(normalized, "dang tin len group")
                || matchesIntent(normalized, "dang tin len nhom")
                || matchesIntent(normalized, "dang trong facebook group")
                || matchesIntent(normalized, "dang len facebook group")
                || matchesIntent(normalized, "dang vao facebook group")
                || matchesIntent(normalized, "post trong group")
                || matchesIntent(normalized, "post trong nhom")
                || matchesIntent(normalized, "post len group")
                || matchesIntent(normalized, "post len nhom")
                || matchesIntent(normalized, "dang facebook group")
                || compact.contains("danggroup")
                || compact.contains("postgroup")
                || compact.contains("dangnhom")
                || compact.contains("postnhom")
                || compact.contains("dangtronggroup")
                || compact.contains("dangtrongnhom")
                || compact.contains("danglengroup")
                || compact.contains("danglennhom")
                || compact.contains("dangvaogroup")
                || compact.contains("dangvaonhom")
                || compact.contains("dangbaitronggroup")
                || compact.contains("dangbaitrongnhom")
                || compact.contains("dangbailengroup")
                || compact.contains("dangbailennhom")
                || compact.contains("dangbaivaogroup")
                || compact.contains("dangbaivaonhom")
                || compact.contains("nggroup")
                || compact.contains("ngnhom");
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
                state.mode = AdminConversationMode.SCHEDULE_MENU;
                sendJobScheduleSummary(adminAccount, senderId);
            }
            case 4 -> {
                if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.VIEW_COUNCIL_REQUESTS)) {
                    sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen xem request tu Hoi dong.");
                    return;
                }
                state.mode = AdminConversationMode.IDLE;
                facebookMessengerService.sendTextMessage(senderId, recruitmentCouncilService.buildPendingHiringRequestsSummary());
                sendAdminMenu(adminAccount, senderId, "Quay ve menu.");
            }
            case 5 -> {
                if (!pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
                    sendAdminMenu(adminAccount, senderId, "Tai khoan nay khong co quyen dang bai Facebook Group.");
                    return;
                }
                processGroupPostIntent(adminAccount, senderId, state,
                        StringUtils.hasText(remainder) ? "dang group " + remainder : "dang group");
            }
            default -> sendAdminMenu(adminAccount, senderId, "Lua chon khong hop le.");
        }
    }

    private void handleCouncilConversation(
            PageAdminAccount councilAccount,
            String senderId,
            AdminConversationState state,
            String sanitizedText
    ) {
        if (state.mode == AdminConversationMode.AWAIT_COUNCIL_HIRING_REQUEST) {
            processCouncilHiringRequest(councilAccount, senderId, state, sanitizedText);
            return;
        }

        CouncilMenuChoice choice = parseCouncilMenuChoice(sanitizedText);
        if (choice == null) {
            sendCouncilMenu(councilAccount, senderId, null);
            return;
        }

        if (choice.option() == 1) {
            if (!pageAdminAccountService.hasPermission(councilAccount, PageAdminPermission.VIEW_COUNCIL_SCHEDULE)) {
                sendCouncilMenu(councilAccount, senderId, "Tai khoan nay khong co quyen xem lich Hoi dong.");
                return;
            }
            facebookMessengerService.sendTextMessage(senderId, interviewSchedulingService.buildCouncilConfirmedSchedule(senderId));
            sendCouncilMenu(councilAccount, senderId, null);
            return;
        }

        if (!pageAdminAccountService.hasPermission(councilAccount, PageAdminPermission.CREATE_HIRING_REQUEST)) {
            sendCouncilMenu(councilAccount, senderId, "Tai khoan nay khong co quyen gui request tuyen thanh vien.");
            return;
        }
        state.mode = AdminConversationMode.AWAIT_COUNCIL_HIRING_REQUEST;
        if (StringUtils.hasText(choice.remainder())) {
            processCouncilHiringRequest(councilAccount, senderId, state, choice.remainder());
        } else {
            facebookMessengerService.sendTextMessage(senderId,
                    "Hay mo ta vi tri can tuyen: vi tri, ky nang, kinh nghiem, muc luong, dia diem, hinh thuc lam viec.");
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

        FacebookPostOperationResponse response = facebookJobPostingService.createAndPublishFromText(text, adminAccount);
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
                .updateFromTextAndRepublish(editRequest.jobId, editRequest.content, adminAccount);
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
                .updateFromTextAndRepublish(state.targetJobId, text, adminAccount);
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
            if (StringUtils.hasText(response.councilSummary())) {
                reply.append(response.councilSummary()).append('\n');
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
        sendScheduleActionMenu(adminAccount, senderId);
    }

    private void sendScheduleActionMenu(PageAdminAccount adminAccount, String senderId) {
        String displayName = StringUtils.hasText(adminAccount.getDisplayName())
                ? adminAccount.getDisplayName()
                : "HR";
        String menu = "Menu HR - Xin chào " + displayName + ":\n"
                + "1. Xem chi tiết lịch\n"
                + "2. Sửa lịch\n"
                + "Nhap 1/2 hoac go ten chuc nang.";
        facebookMessengerService.sendTextMessage(senderId, menu);
    }

    private void processScheduleMenuInput(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            sendAdminMenu(adminAccount, senderId, "Da quay ve menu chinh.");
            return;
        }

        ScheduleSubmenuChoice choice = parseScheduleSubmenuChoice(text);
        if (choice == null) {
            sendScheduleActionMenu(adminAccount, senderId);
            return;
        }

        if (choice.option() == 1) {
            state.mode = AdminConversationMode.AWAIT_SCHEDULE_DETAIL_SLOT_ID;
            facebookMessengerService.sendTextMessage(senderId, "Gui slotId de xem chi tiet lich.");
            return;
        }

        state.mode = AdminConversationMode.AWAIT_SCHEDULE_EDIT_SLOT_ID;
        if (StringUtils.hasText(choice.remainder())) {
            processScheduleEditSelection(adminAccount, senderId, state, choice.remainder());
        } else {
            facebookMessengerService.sendTextMessage(senderId,
                    "Gui theo format: <slotId> <ly do sua lich>, hoac gui <slotId> truoc roi nhap ly do o buoc sau.");
        }
    }

    private void processScheduleDetailRequest(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        Long slotId = parseLeadingLong(text);
        if (slotId == null) {
            facebookMessengerService.sendTextMessage(senderId, "Khong xac dinh duoc slotId. Hay gui lai slotId.");
            return;
        }
        state.mode = AdminConversationMode.SCHEDULE_MENU;
        facebookMessengerService.sendTextMessage(senderId, interviewSchedulingService.buildSlotDetail(slotId));
        sendScheduleActionMenu(adminAccount, senderId);
    }

    private void processScheduleEditSelection(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        AdminJobEditRequest editRequest = parseEditRequest(text);
        if (editRequest.jobId() == null) {
            facebookMessengerService.sendTextMessage(senderId,
                    "Khong xac dinh duoc slotId. Hay gui theo format: <slotId> <ly do sua lich>.");
            return;
        }

        state.targetSlotId = editRequest.jobId();
        if (!StringUtils.hasText(editRequest.content())) {
            state.mode = AdminConversationMode.AWAIT_SCHEDULE_EDIT_REASON;
            facebookMessengerService.sendTextMessage(senderId,
                    "Da nhan slotId=" + editRequest.jobId() + ". Hay gui ly do sua lich.");
            return;
        }

        state.mode = AdminConversationMode.SCHEDULE_MENU;
        String result = interviewSchedulingService.editScheduleByHr(editRequest.jobId(), editRequest.content(), senderId);
        facebookMessengerService.sendTextMessage(senderId, result);
        sendJobScheduleSummary(adminAccount, senderId);
    }

    private void processScheduleEditReason(
            PageAdminAccount adminAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.SCHEDULE_MENU;
            state.targetSlotId = null;
            sendScheduleActionMenu(adminAccount, senderId);
            return;
        }
        if (state.targetSlotId == null) {
            state.mode = AdminConversationMode.SCHEDULE_MENU;
            facebookMessengerService.sendTextMessage(senderId, "Khong tim thay slot dang sua. Moi chon lai.");
            sendScheduleActionMenu(adminAccount, senderId);
            return;
        }

        String result = interviewSchedulingService.editScheduleByHr(state.targetSlotId, text, senderId);
        state.mode = AdminConversationMode.SCHEDULE_MENU;
        state.targetSlotId = null;
        facebookMessengerService.sendTextMessage(senderId, result);
        sendJobScheduleSummary(adminAccount, senderId);
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
            menu.append("1. Đăng bài tự động\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.EDIT_JOB_POST)) {
            menu.append("2. Sửa bài đăng\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.VIEW_HR_SCHEDULE)) {
            menu.append("3. Xem lịch\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.VIEW_COUNCIL_REQUESTS)) {
            menu.append("4. Request từ Hội đồng\n");
            optionCount++;
        }
        if (pageAdminAccountService.hasPermission(adminAccount, PageAdminPermission.AUTO_POST_JOB)) {
            menu.append("5. Đăng bài lên Facebook Group\n");
            optionCount++;
        }

        if (optionCount == 0) {
            menu.append("Tai khoan admin nay chua duoc cap quyen trong database.");
        } else {
            menu.append("Nhập 1/2/3/4/5 hoặc gõ tên chức năng.");
        }

        facebookMessengerService.sendTextMessage(senderId, menu.toString());
    }

    private void sendCouncilMenu(PageAdminAccount councilAccount, String senderId, String prefix) {
        StringBuilder menu = new StringBuilder();
        if (StringUtils.hasText(prefix)) {
            menu.append(prefix).append('\n');
        }
        String displayName = StringUtils.hasText(councilAccount.getDisplayName())
                ? councilAccount.getDisplayName()
                : "ội đồng";
        menu.append("Menu Hội đồng - Xin chao ").append(displayName).append(":\n")
                .append("1. Xem lịch\n")
                .append("2. Tuyển thành viên\n")
                .append("Nhập 1/2 hoặc gõ tên chức năng.");
        facebookMessengerService.sendTextMessage(senderId, menu.toString());
    }

    private AdminMenuChoice parseMenuChoice(String messageText) {
        Matcher prefixMatcher = MENU_PREFIX_PATTERN.matcher(messageText);
        if (prefixMatcher.matches()) {
            int option = Integer.parseInt(prefixMatcher.group(1));
            String remaining = prefixMatcher.group(2) == null ? "" : prefixMatcher.group(2).trim();
            return new AdminMenuChoice(option, remaining);
        }

        return parseIntentMenuChoice(messageText);
    }

    private AdminMenuChoice parseIntentMenuChoice(String messageText) {
        String normalized = normalizeText(messageText);
        if (isAutoPostIntent(normalized)) {
            if (matchesIntent(normalized, "dang bai")) {
                return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
            }
            if (matchesIntent(normalized, "dang tin")) {
                return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
            }
            if (matchesIntent(normalized, "tao bai dang")) {
                return new AdminMenuChoice(1, stripLeadingWords(messageText, 3));
            }
            if (matchesIntent(normalized, "tao post")) {
                return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
            }
            return new AdminMenuChoice(1, "");
        }
        if (matchesIntent(normalized, "dang bai")) {
            return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
        }
        if (matchesIntent(normalized, "dang tin")) {
            return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
        }
        if (matchesIntent(normalized, "tao bai dang")) {
            return new AdminMenuChoice(1, stripLeadingWords(messageText, 3));
        }
        if (matchesIntent(normalized, "tao post")) {
            return new AdminMenuChoice(1, stripLeadingWords(messageText, 2));
        }
        if (matchesIntent(normalized, "sua bai")) {
            return new AdminMenuChoice(2, stripLeadingWords(messageText, 2));
        }
        if (matchesIntent(normalized, "sua bai dang")) {
            return new AdminMenuChoice(2, stripLeadingWords(messageText, 3));
        }
        if (matchesIntent(normalized, "cap nhat bai")) {
            return new AdminMenuChoice(2, stripLeadingWords(messageText, 3));
        }
        if (matchesIntent(normalized, "xem lich")
                || matchesIntent(normalized, "lich voffice")
                || matchesIntent(normalized, "voffice")
                || matchesIntent(normalized, "lich phong van")) {
            return new AdminMenuChoice(3, "");
        }
        if (matchesIntent(normalized, "request")
                || matchesIntent(normalized, "xem request")
                || matchesIntent(normalized, "request hoi dong")
                || matchesIntent(normalized, "request tu hoi dong")
                || matchesIntent(normalized, "yeu cau hoi dong")) {
            return new AdminMenuChoice(4, "");
        }
        return null;
    }

    private boolean isAutoPostIntent(String normalized) {
        String compact = compactText(normalized);
        return matchesIntent(normalized, "dang bai")
                || matchesIntent(normalized, "dang tin")
                || matchesIntent(normalized, "tao bai dang")
                || matchesIntent(normalized, "tao post")
                || compact.contains("dangbai")
                || compact.contains("dangtin")
                || compact.contains("taobaidang")
                || compact.contains("taopost")
                || compact.contains("ngbai")
                || compact.contains("ngboi");
    }

    private CouncilMenuChoice parseCouncilMenuChoice(String messageText) {
        Matcher prefixMatcher = SUBMENU_PREFIX_PATTERN.matcher(messageText);
        if (prefixMatcher.matches()) {
            int option = Integer.parseInt(prefixMatcher.group(1));
            String remaining = prefixMatcher.group(2) == null ? "" : prefixMatcher.group(2).trim();
            return new CouncilMenuChoice(option, remaining);
        }

        String normalized = normalizeText(messageText);
        if (matchesIntent(normalized, "xem lich")
                || matchesIntent(normalized, "lich phong van")
                || matchesIntent(normalized, "lich cua toi")) {
            return new CouncilMenuChoice(1, "");
        }
        if (matchesIntent(normalized, "tuyen thanh vien")
                || matchesIntent(normalized, "can tuyen")
                || matchesIntent(normalized, "request tuyen")) {
            return new CouncilMenuChoice(2, stripLeadingWords(messageText, normalized.startsWith("tuyen thanh vien") ? 3 : 2));
        }
        return null;
    }

    private void processCouncilHiringRequest(
            PageAdminAccount councilAccount,
            String senderId,
            AdminConversationState state,
            String text
    ) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            sendCouncilMenu(councilAccount, senderId, "Da huy thao tac.");
            return;
        }
        if (!StringUtils.hasText(text)) {
            facebookMessengerService.sendTextMessage(senderId, "Hay mo ta noi dung can tuyen.");
            return;
        }

        CouncilHiringRequest request = recruitmentCouncilService.createHiringRequest(councilAccount, text);
        state.mode = AdminConversationMode.IDLE;
        facebookMessengerService.sendTextMessage(senderId,
                "Da gui request tuyen thanh vien cho HR.\n"
                        + "Ma HD: " + request.getCouncilCode() + "\n"
                        + "Ten: " + request.getCouncilName());

        String hrMessage = "Request tuyen thanh vien tu Hoi dong\n"
                + "RequestId: " + request.getId() + "\n"
                + "Ma HD: " + request.getCouncilCode() + "\n"
                + "Ten: " + request.getCouncilName() + "\n"
                + "Noi dung: " + request.getRequestContent();
        for (PageAdminAccount hrAccount : pageAdminAccountService.findActiveHrAccounts()) {
            facebookMessengerService.sendTextMessage(hrAccount.getSenderId(), hrMessage);
        }
        sendCouncilMenu(councilAccount, senderId, null);
    }

    private ScheduleSubmenuChoice parseScheduleSubmenuChoice(String messageText) {
        Matcher prefixMatcher = SUBMENU_PREFIX_PATTERN.matcher(messageText);
        if (prefixMatcher.matches()) {
            int option = Integer.parseInt(prefixMatcher.group(1));
            String remaining = prefixMatcher.group(2) == null ? "" : prefixMatcher.group(2).trim();
            return new ScheduleSubmenuChoice(option, remaining);
        }

        String normalized = normalizeText(messageText);
        if (matchesIntent(normalized, "xem chi tiet lich")
                || matchesIntent(normalized, "xem chi tiet")
                || matchesIntent(normalized, "chi tiet lich")) {
            return new ScheduleSubmenuChoice(1, "");
        }
        if (matchesIntent(normalized, "sua lich")) {
            return new ScheduleSubmenuChoice(2, stripLeadingWords(messageText, 2));
        }
        if (matchesIntent(normalized, "doi lich noi bo")) {
            return new ScheduleSubmenuChoice(2, stripLeadingWords(messageText, 4));
        }
        return null;
    }

    private boolean matchesIntent(String normalizedText, String intent) {
        return normalizedText.equals(intent)
                || normalizedText.startsWith(intent + " ")
                || normalizedText.endsWith(" " + intent)
                || normalizedText.contains(" " + intent + " ");
    }

    private String stripLeadingWords(String originalText, int wordCount) {
        String[] tokens = originalText.trim().split("\\s+");
        if (tokens.length <= wordCount) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(tokens, wordCount, tokens.length));
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

    private Long parseLeadingLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = JOB_ID_PREFIX_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

    private Long parseAnyLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\b(\\d+)\\b").matcher(text.trim());
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private GroupPostSelection parseGroupPostSelection(String text) {
        if (!StringUtils.hasText(text)) {
            return new GroupPostSelection(null, List.of());
        }

        String normalized = normalizeText(text);
        Long jobId = parseNumberAfterKeyword(normalized, "job");
        if (jobId == null) {
            jobId = parseNumberAfterKeyword(normalized, "jd");
        }
        Long groupId = parseNumberAfterKeyword(normalized, "group");
        if (groupId == null) {
            groupId = parseNumberAfterKeyword(normalized, "nhom");
        }

        if (jobId == null || groupId == null) {
            Matcher matcher = Pattern.compile("\\b(\\d+)\\b").matcher(normalized);
            List<Long> numbers = new java.util.ArrayList<>();
            while (matcher.find()) {
                numbers.add(Long.parseLong(matcher.group(1)));
            }
            if (jobId == null && !numbers.isEmpty()) {
                jobId = numbers.get(0);
            }
            if (groupId == null && numbers.size() >= 2) {
                groupId = numbers.get(1);
            }
        }

        return new GroupPostSelection(jobId, groupId == null ? List.of() : List.of(groupId));
    }

    private Long parseNumberAfterKeyword(String normalizedText, String keyword) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\s+(\\d+)\\b").matcher(normalizedText);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
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
        normalized = normalized.replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String compactText(String input) {
        return normalizeText(input).replace(" ", "");
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
        AWAIT_JOB_UPDATE_CONTENT,
        SCHEDULE_MENU,
        AWAIT_SCHEDULE_DETAIL_SLOT_ID,
        AWAIT_SCHEDULE_EDIT_SLOT_ID,
        AWAIT_SCHEDULE_EDIT_REASON,
        AWAIT_COUNCIL_HIRING_REQUEST,
        AWAIT_GROUP_POST_JOB_SELECTION
    }

    private static class AdminConversationState {
        private AdminConversationMode mode = AdminConversationMode.IDLE;
        private Long targetJobId;
        private Long targetSlotId;
        private long lastActiveAt = System.currentTimeMillis();

        void touch() {
            lastActiveAt = System.currentTimeMillis();
        }
    }

    private record AdminMenuChoice(int option, String remainder) {
    }

    private record ScheduleSubmenuChoice(int option, String remainder) {
    }

    private record CouncilMenuChoice(int option, String remainder) {
    }

    private record AdminJobEditRequest(Long jobId, String content) {
    }

    private record GroupPostSelection(Long jobId, List<Long> groupIds) {
    }
}
