package com.trading.strategy.mics;

import com.trading.indicator.BarSeriesCache;
import com.trading.model.Candle;

import java.util.List;

public class VolatilityHedgeDecider {

    private final double optionThreshold;  // ATR% above which we buy a protective option
    private final double spreadThreshold;  // ATR% above which we use a full spread
    private final int    atrPeriod;

    public VolatilityHedgeDecider(double optionThreshold,
                                   double spreadThreshold,
                                   int    atrPeriod) {
        this.optionThreshold = optionThreshold;
        this.spreadThreshold = spreadThreshold;
        this.atrPeriod       = atrPeriod;
    }

    public HedgeMode decide(List<Candle> candles, boolean isBull) {
        if (candles.size() < atrPeriod + 1) return HedgeMode.EQUITY;

        BarSeriesCache cache = BarSeriesCache.of(candles);
        double atr   = cache.atr(atrPeriod);
        double close = candles.get(candles.size() - 1).getClose();

        if (close <= 0 || atr <= 0) return HedgeMode.EQUITY;

        double atrPct = atr / close;

        if (atrPct >= spreadThreshold)  return HedgeMode.SPREAD;
        if (atrPct >= optionThreshold)  return HedgeMode.OPTION_HEDGE;
        return HedgeMode.EQUITY;
    }
}
