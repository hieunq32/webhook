package com.example.recruitmentbot.jobposting.service;

public interface FacebookPageClient {

    FacebookPublishResult publishPost(String message);

    boolean deletePost(String facebookPostId);
}
