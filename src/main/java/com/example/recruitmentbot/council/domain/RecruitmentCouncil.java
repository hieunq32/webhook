package com.example.recruitmentbot.council.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "recruitment_council")
public class RecruitmentCouncil {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 100)
    private String representativeSenderId;

    @Column(length = 255)
    private String interviewerOne;

    @Column(length = 255)
    private String interviewerTwo;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRepresentativeSenderId() {
        return representativeSenderId;
    }

    public void setRepresentativeSenderId(String representativeSenderId) {
        this.representativeSenderId = representativeSenderId;
    }

    public String getInterviewerOne() {
        return interviewerOne;
    }

    public void setInterviewerOne(String interviewerOne) {
        this.interviewerOne = interviewerOne;
    }

    public String getInterviewerTwo() {
        return interviewerTwo;
    }

    public void setInterviewerTwo(String interviewerTwo) {
        this.interviewerTwo = interviewerTwo;
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
}
