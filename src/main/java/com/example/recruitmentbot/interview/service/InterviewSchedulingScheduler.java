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

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void autoStartPassedCandidates() {
        interviewSchedulingService.autoStartSchedulingForPassedCandidates();
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void resetCandidatesWithoutPassCv() {
        interviewSchedulingService.resetSchedulingForCandidatesWithoutPassCv();
    }
}
