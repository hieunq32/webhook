package com.example.recruitmentbot.hradmin.dto;

import com.example.recruitmentbot.hradmin.domain.PageAdminPermission;
import com.example.recruitmentbot.hradmin.domain.PageAdminRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record PageAdminAccountUpsertRequest(
        @NotBlank String senderId,
        String displayName,
        @NotNull PageAdminRole role,
        Set<PageAdminPermission> permissions,
        @NotNull Boolean active
) {
}
