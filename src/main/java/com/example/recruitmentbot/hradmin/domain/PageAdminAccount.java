package com.example.recruitmentbot.hradmin.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "page_admin_account")
public class PageAdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String senderId;

    @Column(length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PageAdminRole role;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "page_admin_account_permission", joinColumns = @JoinColumn(name = "page_admin_account_id"))
    @Column(name = "permission", nullable = false, length = 50)
    private Set<String> permissionValues = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PageAdminRole getRole() {
        return role;
    }

    public void setRole(PageAdminRole role) {
        this.role = role;
    }

    public Set<PageAdminPermission> getPermissions() {
        return permissionValues.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::toPermission)
                .filter(permission -> permission != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setPermissions(Set<PageAdminPermission> permissions) {
        this.permissionValues = permissions == null
                ? new LinkedHashSet<>()
                : permissions.stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    private PageAdminPermission toPermission(String value) {
        try {
            return PageAdminPermission.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
