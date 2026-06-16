package com.example.recruitmentbot.interview.controller;

import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartRequest;
import com.example.recruitmentbot.interview.dto.InterviewSchedulingStartResponse;
import com.example.recruitmentbot.interview.service.InterviewSchedulingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
public class InterviewSchedulingController {

    private final InterviewSchedulingService interviewSchedulingService;

    public InterviewSchedulingController(InterviewSchedulingService interviewSchedulingService) {
        this.interviewSchedulingService = interviewSchedulingService;
    }

    @PostMapping("/scheduling/start")
    public ResponseEntity<InterviewSchedulingStartResponse> startScheduling(
            @Valid @RequestBody InterviewSchedulingStartRequest request
    ) {
        return ResponseEntity.ok(interviewSchedulingService.startScheduling(request));
    }
}
