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
@Table(name = "hr_interview_notification")
public class HrInterviewNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;

    @Column(nullable = false, length = 100)
    private String hrRecipientId;

    @Column(nullable = false)
    private Long interviewSlotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HrInterviewNotificationStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    @Column(nullable = false)
    private OffsetDateTime sentAt;

    @Column(nullable = false)
    private OffsetDateTime responseDeadlineAt;

    @Column
    private OffsetDateTime respondedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getHrRecipientId() {
        return hrRecipientId;
    }

    public void setHrRecipientId(String hrRecipientId) {
        this.hrRecipientId = hrRecipientId;
    }

    public Long getInterviewSlotId() {
        return interviewSlotId;
    }

    public void setInterviewSlotId(Long interviewSlotId) {
        this.interviewSlotId = interviewSlotId;
    }

    public HrInterviewNotificationStatus getStatus() {
        return status;
    }

    public void setStatus(HrInterviewNotificationStatus status) {
        this.status = status;
    }

    public String getMessageBody() {
        return messageBody;
    }

    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(OffsetDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public OffsetDateTime getResponseDeadlineAt() {
        return responseDeadlineAt;
    }

    public void setResponseDeadlineAt(OffsetDateTime responseDeadlineAt) {
        this.responseDeadlineAt = responseDeadlineAt;
    }

    public OffsetDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(OffsetDateTime respondedAt) {
        this.respondedAt = respondedAt;
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
