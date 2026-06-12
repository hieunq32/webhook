package com.example.recruitmentbot.dto.facebook;

public record FacebookSendMessageRequest(
        Recipient recipient,
        Message message
) {
    public record Recipient(String id) {
    }

    public record Message(String text, Attachment attachment) {
        public Message(String text) {
            this(text, null);
        }
    }

    public record Attachment(String type, Payload payload) {
        public static Attachment file(String url) {
            return new Attachment("file", new Payload(url, true));
        }
    }

    public record Payload(String url, Boolean is_reusable) {
    }
}
