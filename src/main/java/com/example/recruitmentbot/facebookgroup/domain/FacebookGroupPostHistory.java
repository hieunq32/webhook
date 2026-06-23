package com.example.recruitmentbot.facebookgroup.domain;

import com.example.recruitmentbot.jobposting.domain.JobDescription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "facebook_group_post_history")
public class FacebookGroupPostHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_description_id", nullable = false)
    private JobDescription jobDescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facebook_group_target_id", nullable = false)
    private FacebookGroupTarget targetGroup;

    @Column(nullable = false)
    private OffsetDateTime postedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FacebookGroupPostStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String generatedContent;

    @Column(columnDefinition = "TEXT")
    private String errorReason;

    @Column(columnDefinition = "TEXT")
    private String openClawResponse;

    @Column(nullable = false)
    private Integer retryCount;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public JobDescription getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(JobDescription jobDescription) {
        this.jobDescription = jobDescription;
    }

    public FacebookGroupTarget getTargetGroup() {
        return targetGroup;
    }

    public void setTargetGroup(FacebookGroupTarget targetGroup) {
        this.targetGroup = targetGroup;
    }

    public OffsetDateTime getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(OffsetDateTime postedAt) {
        this.postedAt = postedAt;
    }

    public FacebookGroupPostStatus getStatus() {
        return status;
    }

    public void setStatus(FacebookGroupPostStatus status) {
        this.status = status;
    }

    public String getGeneratedContent() {
        return generatedContent;
    }

    public void setGeneratedContent(String generatedContent) {
        this.generatedContent = generatedContent;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public String getOpenClawResponse() {
        return openClawResponse;
    }

    public void setOpenClawResponse(String openClawResponse) {
        this.openClawResponse = openClawResponse;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markSuccess(String openClawResponse, int retryCount) {
        this.status = FacebookGroupPostStatus.SUCCESS;
        this.openClawResponse = openClawResponse;
        this.errorReason = null;
        this.retryCount = retryCount;
    }

    public void markFailed(String errorReason, String openClawResponse, int retryCount) {
        this.status = FacebookGroupPostStatus.FAILED;
        this.errorReason = errorReason;
        this.openClawResponse = openClawResponse;
        this.retryCount = retryCount;
    }

    public void markSkipped(String reason) {
        this.status = FacebookGroupPostStatus.SKIPPED;
        this.errorReason = reason;
        this.retryCount = 0;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (postedAt == null) {
            postedAt = now;
        }
        if (status == null) {
            status = FacebookGroupPostStatus.PENDING;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}
