package com.example.recruitmentbot.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean("ollamaRestTemplate")
    public RestTemplate ollamaRestTemplate(RestTemplateBuilder builder, OllamaProperties ollamaProperties) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(Math.max(ollamaProperties.connectTimeoutSeconds(), 1)))
                .setReadTimeout(Duration.ofSeconds(Math.max(ollamaProperties.readTimeoutSeconds(), 1)))
                .build();
    }
}
