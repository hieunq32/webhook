package com.example.recruitmentbot.dto.openai;

import java.util.List;

public record OpenAiResponsesRequest(
        String model,
        boolean store,
        List<InputMessage> input
) {
    public record InputMessage(
            String role,
            String content
    ) {
    }
}
