package com.example.recruitmentbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.recruitmentbot.config.FacebookHrProperties;
import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionResponse;
import com.example.recruitmentbot.jobposting.service.FacebookJobPostingService;
import com.example.recruitmentbot.jobposting.service.JobDescriptionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import java.util.Locale;

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
    private final FacebookJobPostingService facebookJobPostingService;
    private final JobDescriptionService jobDescriptionService;
    private final ExecutorService webhookExecutor = Executors.newCachedThreadPool();
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private final Map<String, AdminConversationState> adminConversationStates = new ConcurrentHashMap<>();

    public MessengerWebhookService(RecruitmentReplyService recruitmentReplyService,
                                   FacebookMessengerService facebookMessengerService,
                                   FacebookHrProperties facebookHrProperties,
                                   FacebookJobPostingService facebookJobPostingService,
                                   JobDescriptionService jobDescriptionService) {
        this.recruitmentReplyService = recruitmentReplyService;
        this.facebookMessengerService = facebookMessengerService;
        this.facebookHrProperties = facebookHrProperties;
        this.facebookJobPostingService = facebookJobPostingService;
        this.jobDescriptionService = jobDescriptionService;
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

            if (isHrAdmin(senderId)) {
                handleAdminConversation(senderId, messageText);
                return;
            }

            handleCandidateConversation(senderId, messageText);
        } catch (Exception exception) {
            log.error("Failed to process Messenger event:\n{}", messagingEvent.toPrettyString(), exception);
        }
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

    private void handleAdminConversation(String senderId, String messageText) {
        if (!StringUtils.hasText(messageText)) {
            return;
        }

        cleanupAdminConversations();
        AdminConversationState state = adminConversationStates.computeIfAbsent(senderId, key -> new AdminConversationState());
        state.touch();

        String sanitizedText = messageText.trim();
        AdminMenuChoice menuChoice = parseMenuChoice(sanitizedText);
        String remainingText = menuChoice == null ? sanitizedText : menuChoice.remainder();

        if (state.mode == AdminConversationMode.IDLE) {
            if (menuChoice != null) {
                processAdminMenuChoice(senderId, state, menuChoice.option(), remainingText);
            } else {
                sendAdminMenu(senderId, "HR mode: bạn vui lòng chọn theo menu.");
            }
            return;
        }

        switch (state.mode) {
            case AWAIT_JOB_DESCRIPTION -> processJobCreationInput(senderId, state, sanitizedText);
            case AWAIT_EDIT_JOB_ID -> processEditJobSelection(senderId, state, sanitizedText);
            case AWAIT_JOB_UPDATE_CONTENT -> processJobUpdateContent(senderId, state, sanitizedText);
            default -> {
                state.mode = AdminConversationMode.IDLE;
                sendAdminMenu(senderId, "Đã trở về menu chính.");
            }
        }
    }

    private void processAdminMenuChoice(String senderId, AdminConversationState state, int option, String remainder) {
        switch (option) {
            case 1 -> {
                state.mode = AdminConversationMode.AWAIT_JOB_DESCRIPTION;
                if (StringUtils.hasText(remainder)) {
                    processJobCreationInput(senderId, state, remainder);
                } else {
                    facebookMessengerService.sendTextMessage(senderId, buildCreatePrompt());
                }
            }
            case 2 -> {
                state.mode = AdminConversationMode.AWAIT_EDIT_JOB_ID;
                state.targetJobId = null;
                if (StringUtils.hasText(remainder)) {
                    processEditJobSelection(senderId, state, remainder);
                } else {
                    facebookMessengerService.sendTextMessage(senderId,
                            "Bạn chọn Sửa bài đăng. Hãy gửi: `<jobId> <nội dung mới>` hoặc chỉ gửi `jobId` để nhập nội dung ở bước tiếp theo.");
                }
            }
            case 3 -> {
                state.mode = AdminConversationMode.IDLE;
                sendJobScheduleSummary(senderId);
            }
            default -> sendAdminMenu(senderId, "Lựa chọn không hợp lệ, hãy chọn lại.");
        }
    }

    private void processJobCreationInput(String senderId, AdminConversationState state, String text) {
        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            sendAdminMenu(senderId, "Đã huỷ thao tác. Về menu.");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService.createAndPublishFromText(text);
        state.mode = AdminConversationMode.IDLE;
        sendAdminFlowResponse(senderId, response);
    }

    private void processEditJobSelection(String senderId, AdminConversationState state, String text) {
        AdminJobEditRequest editRequest = parseEditRequest(text);
        if (editRequest.jobId == null) {
            facebookMessengerService.sendTextMessage(senderId,
                    "Không xác định được jobId. Vui lòng gửi theo format: `<jobId> <nội dung mới>`.");
            return;
        }

        if (!StringUtils.hasText(editRequest.content)) {
            state.mode = AdminConversationMode.AWAIT_JOB_UPDATE_CONTENT;
            state.targetJobId = editRequest.jobId;
            facebookMessengerService.sendTextMessage(senderId,
                    "Đã nhận jobId. Gửi mô tả mới để thay thế và publish lại cho jobId=" + editRequest.jobId + ".");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService
                .updateFromTextAndRepublish(editRequest.jobId, editRequest.content);
        state.mode = AdminConversationMode.IDLE;
        sendAdminFlowResponse(senderId, response);
    }

    private void processJobUpdateContent(String senderId, AdminConversationState state, String text) {
        if (state.targetJobId == null) {
            state.mode = AdminConversationMode.IDLE;
            facebookMessengerService.sendTextMessage(senderId, "Phiên chỉnh sửa không hợp lệ. Vui lòng chọn lại từ đầu.");
            return;
        }

        if ("0".equals(text.trim())) {
            state.mode = AdminConversationMode.IDLE;
            facebookMessengerService.sendTextMessage(senderId, "Đã huỷ chỉnh sửa. Về menu.");
            return;
        }

        FacebookPostOperationResponse response = facebookJobPostingService
                .updateFromTextAndRepublish(state.targetJobId, text);
        state.mode = AdminConversationMode.IDLE;
        state.targetJobId = null;
        sendAdminFlowResponse(senderId, response);
    }

    private void sendAdminFlowResponse(String senderId, FacebookPostOperationResponse response) {
        StringBuilder reply = new StringBuilder();
        if (response.success()) {
            reply.append("Hoàn tất: ").append(response.message()).append('\n');
            reply.append("JobId: ").append(response.jobDescriptionId()).append('\n');
            if (StringUtils.hasText(response.facebookPostId())) {
                reply.append("Facebook Post ID: ").append(response.facebookPostId()).append('\n');
            }
            if (StringUtils.hasText(response.generatedContent())) {
                reply.append("Nội dung đăng: ").append('\n');
                String truncated = truncateText(response.generatedContent(), 900);
                reply.append(truncated);
                if (response.generatedContent().length() > 900) {
                    reply.append(" ...");
                }
            }
        } else {
            reply.append("Không thể hoàn tất: ").append(response.message());
        }
        facebookMessengerService.sendTextMessage(senderId, reply.toString());
        sendAdminMenu(senderId, "Quay về menu để tiếp tục.");
    }

    private void sendJobScheduleSummary(String senderId) {
        List<JobDescriptionResponse> jobs = jobDescriptionService.list();
        if (jobs.isEmpty()) {
            facebookMessengerService.sendTextMessage(senderId, "Hiện chưa có job description nào.");
            sendAdminMenu(senderId, "Bạn có thể tạo mới hoặc sửa bài.");
            return;
        }

        StringBuilder builder = new StringBuilder("Lịch cập nhật tuyển dụng:\n");
        int count = 0;
        for (JobDescriptionResponse job : jobs) {
            if (count++ >= ADMIN_JOB_LIST_LIMIT) {
                break;
            }
            String postStatus = job.activeFacebookPost() != null
                    ? "PostID=" + job.activeFacebookPost().facebookPostId()
                    : "Not posted";
            builder.append(count)
                    .append(". [")
                    .append(job.id())
                    .append("] ")
                    .append(job.title())
                    .append(" | ")
                    .append(job.status())
                    .append(" | ")
                    .append(postStatus)
                    .append('\n');
        }
        if (jobs.size() > ADMIN_JOB_LIST_LIMIT) {
            builder.append("... và ").append(jobs.size() - ADMIN_JOB_LIST_LIMIT)
                    .append(" bản ghi khác. Bạn có thể hỏi kỹ theo jobId để chỉnh sửa.");
        }
        facebookMessengerService.sendTextMessage(senderId, builder.toString());
        sendAdminMenu(senderId, null);
    }

    private void sendAdminMenu(String senderId, String prefix) {
        StringBuilder menu = new StringBuilder();
        if (StringUtils.hasText(prefix)) {
            menu.append(prefix).append('\n');
        }
        menu.append("Menu HR:\n")
                .append("1. Đăng bài tự động\n")
                .append("2. Sửa bài đăng\n")
                .append("3. Xem lịch\n")
                .append("Nhập: 1/2/3 hoặc gõ khóa tương ứng.");
        facebookMessengerService.sendTextMessage(senderId, menu.toString());
    }

    private AdminMenuChoice parseMenuChoice(String messageText) {
        Matcher prefixMatcher = MENU_PREFIX_PATTERN.matcher(messageText);
        if (prefixMatcher.matches()) {
            int option = Integer.parseInt(prefixMatcher.group(1));
            String remaining = prefixMatcher.group(2) == null ? "" : prefixMatcher.group(2).trim();
            return new AdminMenuChoice(option, remaining);
        }

        String normalized = messageText.toLowerCase(Locale.ROOT);
        if (normalized.contains("đăng bài") || normalized.contains("dang bai")) {
            return new AdminMenuChoice(1, messageText.replaceAll("(?i).*đăng bài", "").trim());
        }
        if (normalized.contains("sửa bài") || normalized.contains("sua bai")) {
            return new AdminMenuChoice(2, messageText.replaceAll("(?i).*sửa bài", "").trim());
        }
        if (normalized.contains("xem lịch") || normalized.contains("xem lich")) {
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

        long jobId = Long.parseLong(matcher.group(1));
        String content = matcher.group(2);
        return new AdminJobEditRequest(jobId, content == null || !StringUtils.hasText(content) ? null : content.trim());
    }

    private void cleanupAdminConversations() {
        long now = System.currentTimeMillis();
        adminConversationStates.entrySet().removeIf(entry -> now - entry.getValue().lastActiveAt > ADMIN_SESSION_TTL_MILLIS);
    }

    private boolean isHrAdmin(String senderId) {
        return facebookHrProperties.enabled()
                && StringUtils.hasText(senderId)
                && facebookHrProperties.adminSenderIds().contains(senderId);
    }

    private String buildCreatePrompt() {
        return "Nhập mô tả tin đăng (có thể dạng tự do: vị trí, mô tả, kỹ năng, lương, địa điểm, hình thức làm việc)."
                + " Ví dụ: `Java Developer - 2 năm exp - Node.js, Spring Boot - lương 20-30tr - Hanoi - remote`.";
    }

    private String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
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
