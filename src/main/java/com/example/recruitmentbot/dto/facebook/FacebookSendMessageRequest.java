package com.example.recruitmentbot.dto.facebook;

public record FacebookSendMessageRequest(
        Recipient recipient,
        Message message
) {
    public record Recipient(String id) {
    }

    public record Message(String text) {
    }
}
