package com.trading.execution;

import com.trading.risk.RiskManager;
import com.trading.utils.Shared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic risk status reporter running on a virtual-thread-backed scheduler.
 * Logs a compact P&L + risk summary every N minutes.
 */
public final class TradeMonitor {

    private static final Logger log = LoggerFactory.getLogger(TradeMonitor.class);

    private final RiskManager             riskManager;
    private final OrderManager            orderManager;
    private final ScheduledExecutorService scheduler;

    public TradeMonitor(RiskManager riskManager, OrderManager orderManager) {
        this.riskManager  = riskManager;
        this.orderManager = orderManager;
        this.scheduler    = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("trade-monitor").factory());
    }

    public void start(int intervalMinutes) {
        scheduler.scheduleAtFixedRate(this::report,
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("TradeMonitor started - reporting every {} min", intervalMinutes);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void report() {
        try {
            log.info("-- Trade Monitor ------------------------------------------");
            log.info("  Open positions  : {}", riskManager.getOpenPositions());
            log.info("  Trades today    : {}", riskManager.getTradesToday());
            log.info("  Daily P&L       : Rs.{}", Shared.fmtPnl(riskManager.getDailyProfit() - riskManager.getDailyLoss()));
            log.info("  Daily loss      : Rs.{}", Shared.fmt(riskManager.getDailyLoss()));
            log.info("  Deployed cap    : Rs.{}", Shared.fmt(riskManager.getDeployedCapital()));
            log.info("  Available cap   : Rs.{}", Shared.fmt(riskManager.getAvailableCapital()));
            log.info("  Halted          : {}", riskManager.isHalted());

            var positions = orderManager.getOpenPositions();
            if (!positions.isEmpty()) {
                log.info("  -- Open positions --");
                positions.forEach((key, p) ->
                    log.info("    {} | {} {} x{} @ Rs.{}",
                            p.symbol().isEmpty() ? key : p.symbol(),
                            p.side(), key.contains("|") ? key.split("\\|")[1] : "",
                            p.qty(), String.format("%.2f", p.entryPrice())));
            }
            log.info("-----------------------------------------------------------");
        } catch (Exception e) {
            log.warn("TradeMonitor report error: {}", e.getMessage());
        }
    }
}
