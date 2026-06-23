package com.example.recruitmentbot.facebookgroup.service;

import com.example.recruitmentbot.config.FacebookGroupPostingProperties;
import com.example.recruitmentbot.facebookgroup.dto.FacebookGroupPostSummaryResponse;
import com.example.recruitmentbot.hradmin.domain.PageAdminAccount;
import com.example.recruitmentbot.hradmin.service.PageAdminAccountService;
import com.example.recruitmentbot.service.FacebookMessengerService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FacebookGroupPostingScheduler {

    private static final Logger log = LoggerFactory.getLogger(FacebookGroupPostingScheduler.class);

    private final FacebookGroupPostingService facebookGroupPostingService;
    private final FacebookGroupPostingProperties properties;
    private final PageAdminAccountService pageAdminAccountService;
    private final FacebookMessengerService facebookMessengerService;

    public FacebookGroupPostingScheduler(
            FacebookGroupPostingService facebookGroupPostingService,
            FacebookGroupPostingProperties properties,
            PageAdminAccountService pageAdminAccountService,
            FacebookMessengerService facebookMessengerService
    ) {
        this.facebookGroupPostingService = facebookGroupPostingService;
        this.properties = properties;
        this.pageAdminAccountService = pageAdminAccountService;
        this.facebookMessengerService = facebookMessengerService;
    }

    @Scheduled(cron = "${facebook.group-posting.scheduler-cron}")
    public void publishOpenJobsToGroups() {
        if (!properties.enabled() || !properties.schedulerEnabled()) {
            return;
        }

        List<FacebookGroupPostSummaryResponse> summaries = facebookGroupPostingService.publishOpenJobsByScheduler();
        if (summaries.isEmpty()) {
            return;
        }

        for (PageAdminAccount hrAccount : pageAdminAccountService.findActiveHrAccounts()) {
            for (FacebookGroupPostSummaryResponse summary : summaries) {
                try {
                    facebookMessengerService.sendTextMessage(
                            hrAccount.getSenderId(),
                            facebookGroupPostingService.buildMessengerSummary(summary)
                    );
                } catch (Exception exception) {
                    log.warn("Failed to send scheduled Facebook group posting summary to HR. senderId={}",
                            hrAccount.getSenderId(), exception);
                }
            }
        }
    }
}
