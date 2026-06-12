package com.example.recruitmentbot.jobposting.service;

import com.example.recruitmentbot.jobposting.config.FacebookJobPostingProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class GraphApiFacebookPageClient implements FacebookPageClient {

    private static final Logger log = LoggerFactory.getLogger(GraphApiFacebookPageClient.class);

    private final RestTemplate restTemplate;
    private final FacebookJobPostingProperties properties;

    public GraphApiFacebookPageClient(RestTemplateBuilder builder, FacebookJobPostingProperties properties) {
        this.restTemplate = builder.build();
        this.properties = properties;
    }

    @Override
    public FacebookPublishResult publishPost(String message) {
        String url = properties.graphApiBaseUrl() + "/" + properties.pageId() + "/feed";
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("message", message);
        body.add("access_token", properties.pageAccessToken());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
            String postId = response == null ? null : String.valueOf(response.get("id"));
            if (postId == null || "null".equals(postId)) {
                return new FacebookPublishResult(false, null, "Facebook Graph API did not return post id");
            }
            return new FacebookPublishResult(true, postId, null);
        } catch (RestClientResponseException ex) {
            log.error("Facebook publish API failed. status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            return new FacebookPublishResult(false, null, ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.error("Facebook publish API failed unexpectedly", ex);
            return new FacebookPublishResult(false, null, ex.getMessage());
        }
    }

    @Override
    public boolean deletePost(String facebookPostId) {
        String url = properties.graphApiBaseUrl() + "/" + facebookPostId + "?access_token=" + properties.pageAccessToken();
        try {
            restTemplate.delete(url);
            return true;
        } catch (RestClientResponseException ex) {
            log.error("Facebook delete API failed. postId={}, status={}, body={}",
                    facebookPostId, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            return false;
        } catch (Exception ex) {
            log.error("Facebook delete API failed unexpectedly for postId={}", facebookPostId, ex);
            return false;
        }
    }
}
