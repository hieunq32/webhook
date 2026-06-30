package com.example.recruitmentbot.facebookgroup.service;

public interface FacebookGroupRpaClient {

    FacebookGroupRpaSessionStatus getSessionStatus();

    FacebookGroupRpaSessionStatus startLogin();

    FacebookGroupRpaSessionStatus completeLogin();

    FacebookGroupRpaSessionStatus importSession(com.fasterxml.jackson.databind.JsonNode storageState);

    FacebookGroupRpaResult postToGroup(FacebookGroupRpaRequest request);
}
