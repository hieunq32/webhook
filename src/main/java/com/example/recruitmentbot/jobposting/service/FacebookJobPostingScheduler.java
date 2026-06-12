package com.example.recruitmentbot.jobposting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FacebookJobPostingScheduler {

    private static final Logger log = LoggerFactory.getLogger(FacebookJobPostingScheduler.class);

    private final FacebookJobPostingService facebookJobPostingService;

    public FacebookJobPostingScheduler(FacebookJobPostingService facebookJobPostingService) {
        this.facebookJobPostingService = facebookJobPostingService;
    }

    @Scheduled(cron = "${facebook.job-posting.repost-cron}")
    public void repostStaleJobs() {
        int count = facebookJobPostingService.repostStaleOpenJobs();
        log.info("Scheduled Facebook job repost finished. repostedCount={}", count);
    }
}
