package com.trading.model;

import java.time.LocalDateTime;

public record Position(
        String        token,
        String        symbol,
        String        strategyName,
        String        side,          // "BUY" or "SELL"
        double        entryPrice,
        int           qty,
        double        stopLoss,
        double        takeProfit,
        LocalDateTime openedAt
) {
    public double cost() { return entryPrice * qty; }

    public double unrealisedPnl(double currentPrice) {
        return "BUY".equals(side)
            ? (currentPrice - entryPrice) * qty
            : (entryPrice - currentPrice) * qty;
    }
}
