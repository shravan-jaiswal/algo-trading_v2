package com.trading.scheduler;

import com.trading.risk.RiskManager;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — resets daily risk counters at 9:15 AM IST every trading day.
 */
@DisallowConcurrentExecution
public class DailyResetJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DailyResetJob.class);

    public static final String RISK_KEY = "riskManager";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        RiskManager rm = (RiskManager)
                context.getJobDetail().getJobDataMap().get(RISK_KEY);
        if (rm == null) {
            log.error("RiskManager not found in JobDataMap.");
            return;
        }
        rm.resetDaily();
        log.info("Daily reset job completed at {}", context.getFireTime());
    }
}
