package com.trading.strategy;

import com.trading.model.Candle;

import java.util.List;

public interface Strategy {

    enum Signal {
        LONG,
        LONG_EXIT,
        SHORT,
        SHORT_EXIT,
        NONE;

        public boolean isLongEntry()  { return this == LONG; }
        public boolean isShortEntry() { return this == SHORT; }
        public boolean isLongExit()   { return this == LONG_EXIT || this == SHORT; }
        public boolean isShortExit()  { return this == SHORT_EXIT || this == LONG; }
        public boolean isEntry()      { return this == LONG || this == SHORT; }
        public boolean isExitOnly()   { return this == LONG_EXIT || this == SHORT_EXIT; }
    }

    record StrategyContext(String symbol, String token, List<Candle> candles) {
        public StrategyContext {
            candles = candles == null ? List.of() : List.copyOf(candles);
        }
    }

    String getName();

    Signal evaluate(List<Candle> candles);

    default Signal evaluate(StrategyContext ctx) {
        return evaluate(ctx != null ? ctx.candles() : List.of());
    }

    int getMinCandles();

    /** Live candle timeframe routed to this strategy. Existing strategies remain on 5-minute bars. */
    default String preferredTimeframe() {
        return "FIVE_MINUTE";
    }

    default InstrumentConfig getInstrumentConfig() {
        return InstrumentConfig.EQUITY;
    }

    /**
     * Returns a strategy-defined stop loss price, or -1 to let RiskManager decide.
     */
    default double suggestStopLoss(List<Candle> candles, Signal signal) {
        return -1;
    }

    default double suggestStopLoss(StrategyContext ctx, Signal signal) {
        return suggestStopLoss(ctx != null ? ctx.candles() : List.of(), signal);
    }
}
