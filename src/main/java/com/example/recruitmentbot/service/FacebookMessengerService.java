package com.example.recruitmentbot.service;

import com.example.recruitmentbot.config.FacebookProperties;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest.Message;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest.Attachment;
import com.example.recruitmentbot.dto.facebook.FacebookSendMessageRequest.Recipient;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
        sendMessage(recipientId, new Message(messageText, null));
    }

    public void sendDocumentMessage(String recipientId, String documentUrl) {
        sendMessage(recipientId, new Message(null, Attachment.file(documentUrl)));
    }

    private void sendMessage(String recipientId, Message message) {
        if (!StringUtils.hasText(recipientId) || message == null) {
            return;
        }
        if (!StringUtils.hasText(message.text())
                && (message.attachment() == null
                || message.attachment().payload() == null
                || !StringUtils.hasText(message.attachment().payload().url()))) {
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        FacebookSendMessageRequest request = new FacebookSendMessageRequest(
                new Recipient(recipientId),
                message
        );

        String accessToken = facebookProperties.pageAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("Facebook page-access-token is missing.");
        }
        accessToken = accessToken.trim();
        if (accessToken.equalsIgnoreCase("REPLACE_ME")
                || accessToken.equalsIgnoreCase("your-page-access-token")
                || accessToken.contains("<")
                || accessToken.contains(">")) {
            throw new IllegalStateException("Facebook page-access-token is a placeholder. Set FACEBOOK_PAGE_ACCESS_TOKEN to a valid page access token.");
        }
        if (accessToken.length() < 40) {
            throw new IllegalStateException("Facebook page-access-token looks too short and may be invalid.");
        }

        URI requestUri = UriComponentsBuilder
                .fromUriString(facebookProperties.sendApiUrl())
                .queryParam("access_token", accessToken)
                .build(true)
                .toUri();

        String messageKind = message.attachment() == null ? "text" : "attachment(" + message.attachment().type() + ")";

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    requestUri,
                    new HttpEntity<>(request, headers),
                    String.class
            );

            log.info("Facebook Send API succeeded for recipientId={}, kind={}, status={}, body={}",
                    recipientId, messageKind, response.getStatusCode(), response.getBody());
        } catch (RestClientResponseException exception) {
            String responseBody = exception.getResponseBodyAsString();
            log.error("Facebook Send API failed. recipientId={}, kind={}, status={}, token={}, hint={}, body={}",
                    recipientId,
                    messageKind,
                    exception.getStatusCode(),
                    maskToken(accessToken),
                    buildFacebookErrorHint(responseBody),
                    responseBody,
                    exception);
            throw new IllegalStateException("Facebook Send API call failed: " + buildFacebookErrorHint(responseBody), exception);
        } catch (RestClientException exception) {
            log.error("Facebook Send API call failed before a response was received. recipientId={}, kind={}, token={}",
                    recipientId, messageKind, maskToken(accessToken), exception);
            throw new IllegalStateException("Facebook Send API call failed", exception);
        }
    }

    private String buildFacebookErrorHint(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "No response body from Facebook. Check network access and Send API URL.";
        }

        String normalizedBody = responseBody.toLowerCase();
        if (normalizedBody.contains("invalid oauth access token")
                || normalizedBody.contains("cannot parse access token")
                || normalizedBody.contains("\"code\":190")) {
            return "Invalid page access token. Generate a fresh Page Access Token in Meta Messenger settings and update FACEBOOK_PAGE_ACCESS_TOKEN.";
        }
        if (normalizedBody.contains("permission")
                || normalizedBody.contains("permissions error")
                || normalizedBody.contains("\"code\":10")) {
            return "Missing Messenger permissions. Check app mode, page connection, and subscribed webhook fields.";
        }
        if (normalizedBody.contains("attachment")
                || normalizedBody.contains("url")
                || normalizedBody.contains("unsupported post request")) {
            return "Attachment URL may be inaccessible to Meta. Verify jd-public-base-url is public and the file URL opens outside localhost.";
        }
        return "Inspect Facebook error body for details.";
    }

    private String maskToken(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return "<empty>";
        }
        if (accessToken.length() <= 10) {
            return "****";
        }
        return accessToken.substring(0, 6) + "..." + accessToken.substring(accessToken.length() - 4);
    }
}
