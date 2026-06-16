package com.example.recruitmentbot.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "candidate_profile")
public class CandidateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String senderId;

    @Column(nullable = false, length = 255)
    private String candidateName;

    @Column(nullable = false, length = 255)
    private String appliedPosition;

    @Column(nullable = false, length = 255)
    private String department;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal cvScore;

    @Column
    private Long jobDescriptionId;

    @Column(nullable = false)
    private boolean passCv;

    @Column(nullable = false, length = 100)
    private String assignedHrSenderId;

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

    public Long getJobDescriptionId() {
        return jobDescriptionId;
    }

    public void setJobDescriptionId(Long jobDescriptionId) {
        this.jobDescriptionId = jobDescriptionId;
    }

    public boolean isPassCv() {
        return passCv;
    }

    public void setPassCv(boolean passCv) {
        this.passCv = passCv;
    }

    public String getAssignedHrSenderId() {
        return assignedHrSenderId;
    }

    public void setAssignedHrSenderId(String assignedHrSenderId) {
        this.assignedHrSenderId = assignedHrSenderId;
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
