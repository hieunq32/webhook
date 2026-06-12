package com.example.recruitmentbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final RecruitmentReplyService recruitmentReplyService;
    private final FacebookMessengerService facebookMessengerService;
    private final ExecutorService webhookExecutor = Executors.newCachedThreadPool();
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();

    public MessengerWebhookService(RecruitmentReplyService recruitmentReplyService,
                                   FacebookMessengerService facebookMessengerService) {
        this.recruitmentReplyService = recruitmentReplyService;
        this.facebookMessengerService = facebookMessengerService;
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
        } catch (Exception exception) {
            log.error("Failed to process Messenger event:\n{}", messagingEvent.toPrettyString(), exception);
        }
    }

    private boolean isDuplicateMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }

        long now = System.currentTimeMillis();
        processedMessageIds.entrySet().removeIf(entry -> now - entry.getValue() > MESSAGE_DEDUP_TTL_MILLIS);
        return processedMessageIds.putIfAbsent(messageId, now) != null;
    }
}
