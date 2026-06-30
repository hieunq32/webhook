package com.example.recruitmentbot.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mock.recruitment")
public record RecruitmentMockProperties(
        List<String> greetingKeywords,
        List<String> roleKeywords,
        List<String> cvKeywords,
        List<String> interviewKeywords,
        List<String> salaryKeywords,
        List<String> contactKeywords,
        List<String> statusKeywords,
        List<String> thanksKeywords,
        List<String> outOfScopeKeywords,
        String vietnameseReplyForOutOfScope,
        String englishReplyForOutOfScope,
        String vietnameseFallbackReply,
        String englishFallbackReply,
        Map<String, String> jdDriveLinks,
        String jdRootPath,
        String jdPublicBaseUrl,
        String cvUploadUrl
) {
}
