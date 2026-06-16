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
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "interview_conversation")
public class InterviewConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String candidateSenderId;

    @Column(nullable = false, length = 255)
    private String candidateName;

    @Column(nullable = false, length = 255)
    private String appliedPosition;

    @Column(nullable = false, length = 255)
    private String department;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal cvScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private InterviewConversationState state;

    @Column(columnDefinition = "TEXT")
    private String lastPresentedSlotIds;

    @Column
    private Long selectedSlotId;

    @Column
    private OffsetDateTime selectionLockExpiresAt;

    @Column
    private OffsetDateTime hrDecisionDeadlineAt;

    @Column(columnDefinition = "TEXT")
    private String lastRescheduleReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private InterviewConversationState rescheduleSourceState;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getCandidateSenderId() {
        return candidateSenderId;
    }

    public void setCandidateSenderId(String candidateSenderId) {
        this.candidateSenderId = candidateSenderId;
    }

    public String getCandidateName() {
        return candidateName;
    }

    public void setCandidateName(String candidateName) {
        this.candidateName = candidateName;
    }

    public String getAppliedPosition() {
        return appliedPosition;
    }

    public void setAppliedPosition(String appliedPosition) {
        this.appliedPosition = appliedPosition;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public BigDecimal getCvScore() {
        return cvScore;
    }

    public void setCvScore(BigDecimal cvScore) {
        this.cvScore = cvScore;
    }

    public InterviewConversationState getState() {
        return state;
    }

    public void setState(InterviewConversationState state) {
        this.state = state;
    }

    public String getLastPresentedSlotIds() {
        return lastPresentedSlotIds;
    }

    public void setLastPresentedSlotIds(String lastPresentedSlotIds) {
        this.lastPresentedSlotIds = lastPresentedSlotIds;
    }

    public Long getSelectedSlotId() {
        return selectedSlotId;
    }

    public void setSelectedSlotId(Long selectedSlotId) {
        this.selectedSlotId = selectedSlotId;
    }

    public OffsetDateTime getSelectionLockExpiresAt() {
        return selectionLockExpiresAt;
    }

    public void setSelectionLockExpiresAt(OffsetDateTime selectionLockExpiresAt) {
        this.selectionLockExpiresAt = selectionLockExpiresAt;
    }

    public OffsetDateTime getHrDecisionDeadlineAt() {
        return hrDecisionDeadlineAt;
    }

    public void setHrDecisionDeadlineAt(OffsetDateTime hrDecisionDeadlineAt) {
        this.hrDecisionDeadlineAt = hrDecisionDeadlineAt;
    }

    public String getLastRescheduleReason() {
        return lastRescheduleReason;
    }

    public void setLastRescheduleReason(String lastRescheduleReason) {
        this.lastRescheduleReason = lastRescheduleReason;
    }

    public InterviewConversationState getRescheduleSourceState() {
        return rescheduleSourceState;
    }

    public void setRescheduleSourceState(InterviewConversationState rescheduleSourceState) {
        this.rescheduleSourceState = rescheduleSourceState;
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
}
