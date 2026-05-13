package com.trading.execution;

import com.trading.risk.RiskManager;
import com.trading.strategy.Strategy.Signal;
import com.trading.utils.Shared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks exit conditions for open positions on every candle close.
 * Extracted from the main trading loop for single-responsibility.
 *
 * Exit hierarchy:
 *   1. Signal-based exit (opposite signal from strategy)
 *   2. Trailing Stop Loss hit
 *   3. Take Profit hit
 */
public final class ExitHandler {

    private static final Logger log = LoggerFactory.getLogger(ExitHandler.class);

    private final RiskManager  riskManager;
    private final OrderManager orderManager;

    public ExitHandler(RiskManager riskManager, OrderManager orderManager) {
        this.riskManager  = riskManager;
        this.orderManager = orderManager;
    }

    // ─────────────────────────────────────────────────────────────────
    // LONG EXITS
    // ─────────────────────────────────────────────────────────────────

    /**
     * @return true if the position was exited
     */
    public boolean checkLongExit(String symbol, String token, String exchange,
                                  String positionKey, double currentPrice,
                                  double takeProfit, Signal latestSignal) {

        if (!orderManager.hasOpenPosition(positionKey)) return false;

        // 1. Signal-based exit
        if (latestSignal == Signal.SELL) {
            log.info("Signal exit (SELL) | {} @ Rs.{}", symbol, Shared.fmt(currentPrice));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "SIGNAL");
        }

        // 2. TSL
        riskManager.updateTSL(positionKey, currentPrice);
        if (riskManager.isTSLHit(positionKey, currentPrice)) {
            log.info("TSL exit | {} @ Rs.{} | stop=Rs.{}", symbol,
                    Shared.fmt(currentPrice), Shared.fmt(riskManager.getTSL(positionKey)));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "TSL");
        }

        // 3. Take profit
        if (riskManager.isTakeProfitHit(positionKey, currentPrice, takeProfit)) {
            log.info("TP exit | {} @ Rs.{} | tp=Rs.{}", symbol,
                    Shared.fmt(currentPrice), Shared.fmt(takeProfit));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "TP");
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────
    // SHORT EXITS
    // ─────────────────────────────────────────────────────────────────

    public boolean checkShortExit(String symbol, String token, String exchange,
                                   String positionKey, double currentPrice,
                                   double takeProfit, Signal latestSignal) {

        if (!orderManager.hasOpenPosition(positionKey)) return false;

        if (latestSignal == Signal.BUY) {
            log.info("Signal exit (BUY) on short | {} @ Rs.{}", symbol, Shared.fmt(currentPrice));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "SIGNAL");
        }

        riskManager.updateTSLShort(positionKey, currentPrice);
        if (riskManager.isTSLHitShort(positionKey, currentPrice)) {
            log.info("TSL SHORT exit | {} @ Rs.{}", symbol, Shared.fmt(currentPrice));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "TSL");
        }

        if (riskManager.isTakeProfitHitShort(positionKey, currentPrice, takeProfit)) {
            log.info("TP SHORT exit | {} @ Rs.{}", symbol, Shared.fmt(currentPrice));
            return doExit(symbol, token, exchange, positionKey, currentPrice, "TP");
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────
    // FORCE EXIT (square-off at market close)
    // ─────────────────────────────────────────────────────────────────

    public boolean forceExit(String symbol, String token, String exchange,
                              String positionKey, double currentPrice) {
        if (!orderManager.hasOpenPosition(positionKey)) return false;
        log.info("Force exit (EOD) | {} @ Rs.{}", symbol, Shared.fmt(currentPrice));
        return doExit(symbol, token, exchange, positionKey, currentPrice, "EOD_SQUAREOFF");
    }

    // ─────────────────────────────────────────────────────────────────

    private boolean doExit(String symbol, String token, String exchange,
                            String positionKey, double price, String reason) {
        boolean closed = orderManager.closePosition(symbol, token, exchange, positionKey, price);
        if (closed) {
            log.info("Exit confirmed | {} reason={} @ Rs.{}", symbol, reason, Shared.fmt(price));
        }
        return closed;
    }
}
