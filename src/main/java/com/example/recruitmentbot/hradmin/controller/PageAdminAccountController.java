package com.example.recruitmentbot.hradmin.controller;

import com.example.recruitmentbot.hradmin.dto.PageAdminAccountResponse;
import com.example.recruitmentbot.hradmin.dto.PageAdminAccountUpsertRequest;
import com.example.recruitmentbot.hradmin.service.PageAdminAccountService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/admin-accounts")
public class PageAdminAccountController {

    private final PageAdminAccountService pageAdminAccountService;

    public PageAdminAccountController(PageAdminAccountService pageAdminAccountService) {
        this.pageAdminAccountService = pageAdminAccountService;
    }

    @GetMapping
    public ResponseEntity<List<PageAdminAccountResponse>> list() {
        return ResponseEntity.ok(pageAdminAccountService.list());
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<PageAdminAccountResponse> get(@PathVariable Long accountId) {
        return ResponseEntity.ok(pageAdminAccountService.get(accountId));
    }

    @PostMapping
    public ResponseEntity<PageAdminAccountResponse> create(@Valid @RequestBody PageAdminAccountUpsertRequest request) {
        return ResponseEntity.ok(pageAdminAccountService.create(request));
    }

    @PutMapping("/{accountId}")
    public ResponseEntity<PageAdminAccountResponse> update(
            @PathVariable Long accountId,
            @Valid @RequestBody PageAdminAccountUpsertRequest request
    ) {
        return ResponseEntity.ok(pageAdminAccountService.update(accountId, request));
    }
}
