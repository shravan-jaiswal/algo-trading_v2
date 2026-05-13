package com.trading.strategy;

import com.trading.model.Candle;

import java.util.List;

public interface Strategy {

    enum Signal { BUY, SELL, NONE }

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
