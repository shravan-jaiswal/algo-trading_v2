package com.trading.signal;

import com.trading.strategy.Strategy.Signal;

import java.time.Instant;

public record SignalEvent(
        String   symbol,
        String   token,
        String   strategyName,
        Signal   signal,
        double   suggestedStopLoss,
        double   currentPrice,
        String   reason,
        Instant  timestamp
) {
    public boolean isActionable() {
        return signal != Signal.NONE && currentPrice > 0;
    }

    public boolean isLong()      { return signal.isLongEntry(); }
    public boolean isShort()     { return signal.isShortEntry(); }
    public boolean isLongExit()  { return signal == Signal.LONG_EXIT; }
    public boolean isShortExit() { return signal == Signal.SHORT_EXIT; }

    @Deprecated public boolean isBuy()  { return isLong(); }
    @Deprecated public boolean isSell() { return isShort(); }

    @Override
    public String toString() {
        return "SignalEvent[%s %s %s @ Rs.%.2f sl=%.2f reason=%s]"
                .formatted(strategyName, signal, symbol, currentPrice, suggestedStopLoss, reason);
    }
}
