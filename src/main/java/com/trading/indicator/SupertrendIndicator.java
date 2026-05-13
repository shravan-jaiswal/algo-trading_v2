package com.trading.indicator;

import com.trading.model.Candle;

import java.util.List;

public final class SupertrendIndicator {

    private SupertrendIndicator() {}

    public record Result(double stopLine, boolean bullish) {}

    /**
     * Calculates Supertrend for the last bar of the candle list.
     * Returns null if there is insufficient data.
     */
    public static Result calculate(List<Candle> candles, int atrPeriod, double multiplier) {
        if (candles.size() < atrPeriod + 2) return null;

        // Compute ATR using BarSeriesCache
        double atr = BarSeriesCache.of(candles).atr(atrPeriod);
        if (atr <= 0) return null;

        Candle latest = candles.get(candles.size() - 1);
        double hl2    = (latest.getHigh() + latest.getLow()) / 2.0;

        double upperBand = hl2 + multiplier * atr;
        double lowerBand = hl2 - multiplier * atr;

        // Simplified: use close vs bands as direction proxy
        double close = latest.getClose();
        boolean bullish = close > lowerBand;

        double stopLine = bullish ? lowerBand : upperBand;

        return new Result(stopLine, bullish);
    }
}
