package com.example.recruitmentbot.interview.service;

import com.example.recruitmentbot.service.FacebookMessengerService;
import org.springframework.stereotype.Service;

@Service
public class FacebookChannelMessagingService implements ChannelMessagingService {

    private final FacebookMessengerService facebookMessengerService;

    public FacebookChannelMessagingService(FacebookMessengerService facebookMessengerService) {
        this.facebookMessengerService = facebookMessengerService;
    }

    @Override
    public void sendText(String recipientId, String messageText) {
        facebookMessengerService.sendTextMessage(recipientId, messageText);
    }
}
