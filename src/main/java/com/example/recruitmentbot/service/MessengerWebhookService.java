package com.example.recruitmentbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MessengerWebhookService {

    private static final Logger log = LoggerFactory.getLogger(MessengerWebhookService.class);
    private static final String OPENAI_UNAVAILABLE_MESSAGE =
            "Thanks for your message. I'm the recruitment assistant for this page, but I'm temporarily unavailable. Please try again shortly.";

    private final OpenAiService openAiService;
    private final FacebookMessengerService facebookMessengerService;

    public MessengerWebhookService(OpenAiService openAiService, FacebookMessengerService facebookMessengerService) {
        this.openAiService = openAiService;
        this.facebookMessengerService = facebookMessengerService;
    }

    public void processIncomingWebhook(JsonNode payload) {
        if (isDirectMessagingEvent(payload)) {
            processMessagingEvent(payload);
            return;
        }

        if (!"page".equals(payload.path("object").asText())) {
            log.warn("Ignoring unsupported Facebook webhook object: {}", payload.path("object").asText());
            return;
        }

        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode messagingEvent : entry.path("messaging")) {
                processMessagingEvent(messagingEvent);
            }
        }
    }

    private boolean isDirectMessagingEvent(JsonNode payload) {
        return payload.has("sender") && payload.has("message");
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

            String senderId = messagingEvent.path("sender").path("id").asText(null);
            String messageText = messageNode.path("text").asText(null);

            if (!StringUtils.hasText(senderId)) {
                log.warn("Skipping message event because sender id is missing: {}", messagingEvent.toPrettyString());
                return;
            }

            if (!StringUtils.hasText(messageText)) {
                log.info("Ignoring non-text message from senderId={}", senderId);
                return;
            }

            log.info("Received candidate text message from senderId={}: {}", senderId, messageText);

            String replyText;
            try {
                replyText = openAiService.generateRecruitmentReply(messageText);
            } catch (Exception exception) {
                log.error("Failed to generate OpenAI reply for senderId={}", senderId, exception);
                replyText = OPENAI_UNAVAILABLE_MESSAGE;
            }

            facebookMessengerService.sendTextMessage(senderId, replyText);
        } catch (Exception exception) {
            log.error("Failed to process Messenger event:\n{}", messagingEvent.toPrettyString(), exception);
        }
    }
}
