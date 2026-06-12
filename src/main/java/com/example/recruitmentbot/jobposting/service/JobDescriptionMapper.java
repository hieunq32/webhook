package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.domain.FacebookJobPost;
import com.example.recruitmentbot.jobposting.domain.JobDescription;
import com.example.recruitmentbot.jobposting.dto.FacebookPostResponse;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JobDescriptionMapper {

    public JobDescriptionResponse toResponse(JobDescription jobDescription, Optional<FacebookJobPost> activePost) {
        return new JobDescriptionResponse(
                jobDescription.getId(),
                jobDescription.getTitle(),
                jobDescription.getDescription(),
                jobDescription.getRequirements(),
                jobDescription.getSalary(),
                jobDescription.getLocation(),
                jobDescription.getWorkType(),
                jobDescription.getStatus(),
                jobDescription.getApplicantCount(),
                jobDescription.getCreatedAt(),
                jobDescription.getUpdatedAt(),
                activePost.map(this::toFacebookPostResponse).orElse(null)
        );
    }

    public FacebookPostResponse toFacebookPostResponse(FacebookJobPost post) {
        return new FacebookPostResponse(
                post.getId(),
                post.getFacebookPostId(),
                post.getGeneratedContent(),
                post.getStatus(),
                post.getPostedAt(),
                post.getDeletedAt()
        );
    }
}
