package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.FacebookProperties;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest.Message;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest.Recipient;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class FacebookMessengerService {

    private static final Logger log = LoggerFactory.getLogger(FacebookMessengerService.class);

    private final RestTemplate restTemplate;
    private final FacebookProperties facebookProperties;

    public FacebookMessengerService(RestTemplate restTemplate, FacebookProperties facebookProperties) {
        this.restTemplate = restTemplate;
        this.facebookProperties = facebookProperties;
    }

    public void sendTextMessage(String recipientId, String messageText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        FacebookSendMessageRequest request = new FacebookSendMessageRequest(
                new Recipient(recipientId),
                new Message(messageText)
        );

        URI requestUri = UriComponentsBuilder
                .fromUriString(facebookProperties.sendApiUrl())
                .queryParam("access_token", facebookProperties.pageAccessToken())
                .build(true)
                .toUri();

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    requestUri,
                    new HttpEntity<>(request, headers),
                    String.class
            );

            log.info("Facebook Send API succeeded for recipientId={}. status={}, body={}",
                    recipientId, response.getStatusCode(), response.getBody());
        } catch (RestClientResponseException exception) {
            log.error("Facebook Send API returned an error. status={}, body={}",
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
            throw new IllegalStateException("Facebook Send API call failed", exception);
        } catch (RestClientException exception) {
            log.error("Facebook Send API call failed before a response was received", exception);
            throw new IllegalStateException("Facebook Send API call failed", exception);
        }
    }
}
