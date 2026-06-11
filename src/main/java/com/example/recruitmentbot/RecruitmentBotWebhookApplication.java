package com.example.recruitmentbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RecruitmentBotWebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecruitmentBotWebhookApplication.class, args);
    }
}
