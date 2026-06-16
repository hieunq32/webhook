package com.example.recruitmentbot.interview.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InterviewSchedulingScheduler {

    private final InterviewSchedulingService interviewSchedulingService;

    public InterviewSchedulingScheduler(InterviewSchedulingService interviewSchedulingService) {
        this.interviewSchedulingService = interviewSchedulingService;
    }

    @Scheduled(fixedDelay = 60000)
    public void releaseExpiredSoftLocks() {
        interviewSchedulingService.releaseExpiredSoftLocks();
    }

    @Scheduled(fixedDelay = 60000)
    public void autoConfirmHrTimeouts() {
        interviewSchedulingService.autoConfirmExpiredHrNotifications();
    }
}
