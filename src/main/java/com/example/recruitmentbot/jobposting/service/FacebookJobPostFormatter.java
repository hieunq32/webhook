package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.domain.WorkType;
import org.springframework.stereotype.Component;

@Component
public class FacebookJobPostFormatter {

    public String format(JobDescription jobDescription) {
        return """
                %s

                📍 Dia diem: %s
                💼 Hinh thuc: %s
                💰 Muc luong: %s

                📋 Mo ta cong viec:
                %s

                ✅ Yeu cau:
                %s

                Inbox fanpage de duoc tu van them va nhan huong dan ung tuyen.
                """.formatted(
                "🚀 [Tuyen dung] " + jobDescription.getTitle(),
                jobDescription.getLocation(),
                toVietnameseWorkType(jobDescription.getWorkType()),
                jobDescription.getSalary(),
                jobDescription.getDescription(),
                jobDescription.getRequirements()
        ).trim();
    }

    private String toVietnameseWorkType(WorkType workType) {
        return switch (workType) {
            case REMOTE -> "Remote";
            case ONSITE -> "Onsite";
            case HYBRID -> "Hybrid";
        };
    }
}
