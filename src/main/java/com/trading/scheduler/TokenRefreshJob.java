package com.trading.scheduler;

import com.trading.broker.AngelOneClient;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — refreshes Angel One session tokens every 8 hours.
 * Register with Quartz scheduler in Application startup.
 */
@DisallowConcurrentExecution
public class TokenRefreshJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshJob.class);

    public static final String CLIENT_KEY = "angelOneClient";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        AngelOneClient client = (AngelOneClient)
                context.getJobDetail().getJobDataMap().get(CLIENT_KEY);
        if (client == null) {
            log.error("AngelOneClient not found in JobDataMap.");
            return;
        }
        try {
            client.refreshSession();
            log.info("Token refresh job completed.");
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
        }
    }
}
