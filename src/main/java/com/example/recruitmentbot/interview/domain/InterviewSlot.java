package com.example.recruitmentbot.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "interview_slot")
public class InterviewSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String slotKey;

    @Column(nullable = false, length = 255)
    private String department;

    @Column(nullable = false)
    private OffsetDateTime startTime;

    @Column(nullable = false)
    private OffsetDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewSlotStatus status;

    @Column
    private Long lockedByConversationId;

    @Column
    private OffsetDateTime lockExpiresAt;

    @Column
    private Long bookedByConversationId;

    @Column
    private OffsetDateTime bookedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public void setSlotKey(String slotKey) {
        this.slotKey = slotKey;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    public InterviewSlotStatus getStatus() {
        return status;
    }

    public void setStatus(InterviewSlotStatus status) {
        this.status = status;
    }

    public Long getLockedByConversationId() {
        return lockedByConversationId;
    }

    public void setLockedByConversationId(Long lockedByConversationId) {
        this.lockedByConversationId = lockedByConversationId;
    }

    public OffsetDateTime getLockExpiresAt() {
        return lockExpiresAt;
    }

    public void setLockExpiresAt(OffsetDateTime lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }

    public Long getBookedByConversationId() {
        return bookedByConversationId;
    }

    public void setBookedByConversationId(Long bookedByConversationId) {
        this.bookedByConversationId = bookedByConversationId;
    }

    public OffsetDateTime getBookedAt() {
        return bookedAt;
    }

    public void setBookedAt(OffsetDateTime bookedAt) {
        this.bookedAt = bookedAt;
    }

    public boolean isExpiredSoftLock(OffsetDateTime now) {
        return status == InterviewSlotStatus.SOFT_LOCKED
                && lockExpiresAt != null
                && lockExpiresAt.isBefore(now);
    }

    public void softLock(Long conversationId, OffsetDateTime expiresAt) {
        status = InterviewSlotStatus.SOFT_LOCKED;
        lockedByConversationId = conversationId;
        lockExpiresAt = expiresAt;
        bookedByConversationId = null;
        bookedAt = null;
    }

    public void book(Long conversationId) {
        status = InterviewSlotStatus.BOOKED;
        bookedByConversationId = conversationId;
        bookedAt = OffsetDateTime.now();
        lockedByConversationId = null;
        lockExpiresAt = null;
    }

    public void release() {
        status = InterviewSlotStatus.AVAILABLE;
        lockedByConversationId = null;
        lockExpiresAt = null;
        if (bookedByConversationId == null) {
            bookedAt = null;
        }
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
}
