package com.example.recruitmentbot.jobposting.controller;

import com.example.recruitmentbot.jobposting.dto.FacebookPostOperationResponse;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionResponse;
import com.example.recruitmentbot.jobposting.dto.JobDescriptionUpsertRequest;
import com.example.recruitmentbot.jobposting.dto.JobStatusUpdateRequest;
import com.example.recruitmentbot.jobposting.service.FacebookJobPostingService;
import com.example.recruitmentbot.jobposting.service.JobDescriptionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/jobs")
public class JobPostingAdminController {

    private final JobDescriptionService jobDescriptionService;
    private final FacebookJobPostingService facebookJobPostingService;

    public JobPostingAdminController(
            JobDescriptionService jobDescriptionService,
            FacebookJobPostingService facebookJobPostingService
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.facebookJobPostingService = facebookJobPostingService;
    }

    @PostMapping
    public ResponseEntity<JobDescriptionResponse> create(@Valid @RequestBody JobDescriptionUpsertRequest request) {
        return ResponseEntity.ok(jobDescriptionService.create(request));
    }

    @PutMapping("/{jobId}")
    public ResponseEntity<JobDescriptionResponse> update(
            @PathVariable Long jobId,
            @Valid @RequestBody JobDescriptionUpsertRequest request
    ) {
        jobDescriptionService.update(jobId, request);
        facebookJobPostingService.handleJobStatusChange(jobId, request.status());
        return ResponseEntity.ok(jobDescriptionService.get(jobId));
    }

    @GetMapping
    public ResponseEntity<List<JobDescriptionResponse>> list() {
        return ResponseEntity.ok(jobDescriptionService.list());
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobDescriptionResponse> get(@PathVariable Long jobId) {
        return ResponseEntity.ok(jobDescriptionService.get(jobId));
    }

    @PutMapping("/{jobId}/status")
    public ResponseEntity<JobDescriptionResponse> updateStatus(
            @PathVariable Long jobId,
            @Valid @RequestBody JobStatusUpdateRequest request
    ) {
        jobDescriptionService.updateStatus(jobId, request.status());
        facebookJobPostingService.handleJobStatusChange(jobId, request.status());
        return ResponseEntity.ok(jobDescriptionService.get(jobId));
    }

    @PostMapping("/{jobId}/facebook-post")
    public ResponseEntity<FacebookPostOperationResponse> publish(@PathVariable Long jobId) {
        return ResponseEntity.ok(facebookJobPostingService.publish(jobId));
    }

    @PostMapping("/{jobId}/facebook-post/repost")
    public ResponseEntity<FacebookPostOperationResponse> repost(@PathVariable Long jobId) {
        return ResponseEntity.ok(facebookJobPostingService.repost(jobId));
    }

    @DeleteMapping("/{jobId}/facebook-post")
    public ResponseEntity<FacebookPostOperationResponse> delete(@PathVariable Long jobId) {
        return ResponseEntity.ok(facebookJobPostingService.delete(jobId));
    }

    @PostMapping("/publish")
    public ResponseEntity<FacebookPostOperationResponse> createAndPublish(
            @Valid @RequestBody JobDescriptionUpsertRequest request
    ) {
        return ResponseEntity.ok(facebookJobPostingService.createAndPublish(request));
    }

    @PutMapping("/{jobId}/publish")
    public ResponseEntity<FacebookPostOperationResponse> updateAndRepublish(
            @PathVariable Long jobId,
            @Valid @RequestBody JobDescriptionUpsertRequest request
    ) {
        return ResponseEntity.ok(facebookJobPostingService.updateAndRepublish(jobId, request));
    }
}
