package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.domain.JobDescription;

public interface JobPostContentGenerator {

    String generatePost(JobDescription jobDescription);
}
