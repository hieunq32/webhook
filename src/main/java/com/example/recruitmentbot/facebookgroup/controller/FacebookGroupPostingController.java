package com.example.recruitmentbot.facebookgroup.controller;

import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostHistoryResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetRequest;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupTargetResponse;
import com.example.recruitmentbot.facebookgroup.service.FacebookGroupPostingService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/facebook-groups")
public class FacebookGroupPostingController {

    private final FacebookGroupPostingService facebookGroupPostingService;

    public FacebookGroupPostingController(FacebookGroupPostingService facebookGroupPostingService) {
        this.facebookGroupPostingService = facebookGroupPostingService;
    }

    @GetMapping
    public ResponseEntity<List<FacebookGroupTargetResponse>> listGroups() {
        return ResponseEntity.ok(facebookGroupPostingService.listGroups());
    }

    @PostMapping
    public ResponseEntity<FacebookGroupTargetResponse> createGroup(@Valid @RequestBody FacebookGroupTargetRequest request) {
        return ResponseEntity.ok(facebookGroupPostingService.createGroup(request));
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<FacebookGroupTargetResponse> updateGroup(
            @PathVariable Long groupId,
            @Valid @RequestBody FacebookGroupTargetRequest request
    ) {
        return ResponseEntity.ok(facebookGroupPostingService.updateGroup(groupId, request));
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<FacebookGroupTargetResponse> deactivateGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(facebookGroupPostingService.deactivateGroup(groupId));
    }

    @PostMapping("/jobs/{jobId}/post")
    public ResponseEntity<FacebookGroupPostSummaryResponse> publishJobToGroups(
            @PathVariable Long jobId,
            @RequestParam(required = false) String hrSenderId
    ) {
        return ResponseEntity.ok(facebookGroupPostingService.publishJobToActiveGroups(jobId, hrSenderId, "rest"));
    }

    @GetMapping("/history")
    public ResponseEntity<List<FacebookGroupPostHistoryResponse>> history() {
        return ResponseEntity.ok(facebookGroupPostingService.listRecentHistory());
    }
}
