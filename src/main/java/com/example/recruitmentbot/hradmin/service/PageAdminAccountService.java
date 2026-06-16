package com.example.recruitmentbot.hradmin.service;

import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import com.example.recruitmentbot.hradmin.domain.PageAdminPermission;
import com.example.recruitmentbot.hradmin.domain.PageAdminRole;
import com.example.recruitmentbot.hradmin.dto.PageAdminAccountResponse;
import com.example.recruitmentbot.hradmin.dto.PageAdminAccountUpsertRequest;
import com.example.recruitmentbot.hradmin.repository.PageAdminAccountRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PageAdminAccountService {

    private final PageAdminAccountRepository pageAdminAccountRepository;

    public PageAdminAccountService(PageAdminAccountRepository pageAdminAccountRepository) {
        this.pageAdminAccountRepository = pageAdminAccountRepository;
    }

    @Transactional(readOnly = true)
    public Optional<PageAdminAccount> findActiveBySenderId(String senderId) {
        if (!StringUtils.hasText(senderId)) {
            return Optional.empty();
        }
        return pageAdminAccountRepository.findFirstBySenderIdAndActiveTrue(senderId.trim());
    }

    @Transactional(readOnly = true)
    public List<PageAdminAccount> findActiveHrAccounts() {
        return pageAdminAccountRepository.findAllByActiveTrue().stream()
                .filter(account -> account.getRole() == PageAdminRole.SUPER_ADMIN
                        || account.getRole() == PageAdminRole.HR_MANAGER
                        || account.getRole() == PageAdminRole.RECRUITER)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(PageAdminAccount account, PageAdminPermission permission) {
        return account != null
                && account.isActive()
                && account.getPermissions() != null
                && account.getPermissions().contains(permission);
    }

    @Transactional(readOnly = true)
    public List<PageAdminAccountResponse> list() {
        return pageAdminAccountRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageAdminAccountResponse get(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional
    public PageAdminAccountResponse create(PageAdminAccountUpsertRequest request) {
        PageAdminAccount account = new PageAdminAccount();
        apply(account, request);
        return toResponse(pageAdminAccountRepository.save(account));
    }

    @Transactional
    public PageAdminAccountResponse update(Long id, PageAdminAccountUpsertRequest request) {
        PageAdminAccount account = getEntity(id);
        apply(account, request);
        return toResponse(pageAdminAccountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public PageAdminAccount getEntity(Long id) {
        return pageAdminAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page admin account not found: " + id));
    }

    private void apply(PageAdminAccount account, PageAdminAccountUpsertRequest request) {
        account.setSenderId(request.senderId().trim());
        account.setDisplayName(StringUtils.hasText(request.displayName()) ? request.displayName().trim() : null);
        account.setRole(request.role());
        account.setActive(Boolean.TRUE.equals(request.active()));
        account.setPermissions(resolvePermissions(request.role(), request.permissions()));
    }

    private Set<PageAdminPermission> resolvePermissions(PageAdminRole role, Set<PageAdminPermission> explicitPermissions) {
        if (explicitPermissions != null && !explicitPermissions.isEmpty()) {
            return EnumSet.copyOf(explicitPermissions);
        }

        return switch (role) {
            case SUPER_ADMIN, HR_MANAGER, RECRUITER -> EnumSet.of(
                    PageAdminPermission.AUTO_POST_JOB,
                    PageAdminPermission.EDIT_JOB_POST,
                    PageAdminPermission.VIEW_HR_SCHEDULE,
                    PageAdminPermission.VIEW_COUNCIL_REQUESTS
            );
            case COUNCIL -> EnumSet.of(
                    PageAdminPermission.VIEW_COUNCIL_SCHEDULE,
                    PageAdminPermission.CREATE_HIRING_REQUEST
            );
            case VIEWER -> EnumSet.of(PageAdminPermission.VIEW_HR_SCHEDULE);
        };
    }

    private PageAdminAccountResponse toResponse(PageAdminAccount account) {
        return new PageAdminAccountResponse(
                account.getId(),
                account.getSenderId(),
                account.getDisplayName(),
                account.getRole(),
                account.getPermissions(),
                account.isActive(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
