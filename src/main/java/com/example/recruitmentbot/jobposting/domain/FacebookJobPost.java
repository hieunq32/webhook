package com.example.recruitmentbot.jobposting.domain;

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
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "facebook_post")
public class FacebookJobPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_description_id", nullable = false)
    private JobDescription jobDescription;

    @Column(nullable = false, unique = true, length = 255)
    private String facebookPostId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String generatedContent;

    @Column(nullable = false)
    private OffsetDateTime postedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FacebookPostStatus status;

    @Column
    private OffsetDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public JobDescription getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(JobDescription jobDescription) {
        this.jobDescription = jobDescription;
    }

    public String getFacebookPostId() {
        return facebookPostId;
    }

    public void setFacebookPostId(String facebookPostId) {
        this.facebookPostId = facebookPostId;
    }

    public String getGeneratedContent() {
        return generatedContent;
    }

    public void setGeneratedContent(String generatedContent) {
        this.generatedContent = generatedContent;
    }

    public OffsetDateTime getPostedAt() {
        return postedAt;
    }

    public FacebookPostStatus getStatus() {
        return status;
    }

    public void setStatus(FacebookPostStatus status) {
        this.status = status;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted() {
        this.status = FacebookPostStatus.DELETED;
        this.deletedAt = OffsetDateTime.now();
    }

    @PrePersist
    void onCreate() {
        if (postedAt == null) {
            postedAt = OffsetDateTime.now();
        }
        if (generatedContent == null) {
            generatedContent = "";
        }
    }
}
