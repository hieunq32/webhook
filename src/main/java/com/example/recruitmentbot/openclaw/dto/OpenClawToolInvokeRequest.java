package com.example.recruitmentbot.openclaw.dto;

import java.util.Map;

public record OpenClawToolInvokeRequest(
        String tool,
        String action,
        Map<String, Object> args,
        String sessionKey
) {
}
