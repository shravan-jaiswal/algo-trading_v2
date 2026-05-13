package com.trading.model;

import java.time.LocalDateTime;

public record TradeLog(
        int           id,
        String        token,
        String        symbol,
        String        strategyName,
        String        side,
        double        entryPrice,
        double        exitPrice,
        int           qty,
        double        pnl,
        String        exitReason,
        LocalDateTime entryTime,
        LocalDateTime exitTime
) {
    public static TradeLog of(String token, String symbol, String strategyName,
                               String side, double entry, double exit, int qty,
                               String exitReason) {
        double pnl = "BUY".equalsIgnoreCase(side)
                ? (exit - entry) * qty
                : (entry - exit) * qty;
        return new TradeLog(0, token, symbol, strategyName, side,
                entry, exit, qty, pnl, exitReason,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
    }
}
