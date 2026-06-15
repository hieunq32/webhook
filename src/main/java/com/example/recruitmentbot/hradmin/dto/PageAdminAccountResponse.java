package com.example.recruitmentbot.hradmin.dto;

import com.example.recruitmentbot.hradmin.domain.PageAdminPermission;
import com.example.recruitmentbot.hradmin.domain.PageAdminRole;
import java.time.OffsetDateTime;
import java.util.Set;

public record PageAdminAccountResponse(
        Long id,
        String senderId,
        String displayName,
        PageAdminRole role,
        Set<PageAdminPermission> permissions,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
