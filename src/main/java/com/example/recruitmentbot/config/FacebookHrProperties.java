package com.example.recruitmentbot.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "facebook.hr")
public class FacebookHrProperties {

    private boolean enabled = true;
    @Valid
    @NotNull
    private List<String> adminSenderIds = new ArrayList<>();

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> adminSenderIds() {
        return Collections.unmodifiableList(adminSenderIds);
    }

    public void setAdminSenderIds(List<String> adminSenderIds) {
        this.adminSenderIds = adminSenderIds == null ? new ArrayList<>() : adminSenderIds;
    }
}

