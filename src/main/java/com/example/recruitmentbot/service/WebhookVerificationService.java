package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.FacebookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WebhookVerificationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookVerificationService.class);

    private final FacebookProperties facebookProperties;

    public WebhookVerificationService(FacebookProperties facebookProperties) {
        this.facebookProperties = facebookProperties;
    }

    public ResponseEntity<String> verifyWebhook(String mode, String verifyToken, String challenge) {
        if (!StringUtils.hasText(mode) || !StringUtils.hasText(verifyToken) || !StringUtils.hasText(challenge)) {
            log.warn("Webhook verification request is missing required query parameters. mode={}, challengePresent={}",
                    mode, StringUtils.hasText(challenge));
            return ResponseEntity.badRequest().body("Missing required verification parameters");
        }

        if (!"subscribe".equals(mode)) {
            log.warn("Unsupported webhook verification mode received: {}", mode);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Unsupported verification mode");
        }

        if (!facebookProperties.verifyToken().equals(verifyToken)) {
            log.warn("Webhook verification failed because verify token did not match the configured value");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid verify token");
        }

        log.info("Facebook webhook verification succeeded");
        return ResponseEntity.ok(challenge);
    }
}
