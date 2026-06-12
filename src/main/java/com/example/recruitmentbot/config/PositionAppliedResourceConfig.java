package com.example.recruitmentbot.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PositionAppliedResourceConfig implements WebMvcConfigurer {

    private final RecruitmentMockProperties mockProperties;

    public PositionAppliedResourceConfig(RecruitmentMockProperties mockProperties) {
        this.mockProperties = mockProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(mockProperties.jdRootPath())) {
            return;
        }

        Path root = Path.of(mockProperties.jdRootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }

        String location = root.toUri().toString().endsWith("/") ? root.toUri().toString() : root.toUri() + "/";
        registry.addResourceHandler("/positionApplied/**")
                .addResourceLocations(location);
    }
}
