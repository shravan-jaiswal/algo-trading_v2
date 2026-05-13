package com.trading.indicator;

import com.trading.model.Candle;

import java.util.List;

/**
 * Stateless helper — delegates to BarSeriesCache.
 * Kept for backward compatibility with strategy code that calls static methods.
 * New code should create a BarSeriesCache directly to share one series build.
 */
public final class IndicatorSupport {

    private IndicatorSupport() {}

    public static double ema(List<Candle> candles, int period) {
        return BarSeriesCache.of(candles).ema(period);
    }

    public static double sma(List<Candle> candles, int period) {
        return BarSeriesCache.of(candles).sma(period);
    }

    /** Returns RSI at the last bar index — use BarSeriesCache.rsi() for consistent API. */
    public static double rsi(List<Candle> candles, int period, int index) {
        if (candles.isEmpty() || index != candles.size() - 1) return 0;
        return BarSeriesCache.of(candles).rsi(period);
    }

    /** RSI at last bar — preferred overload. */
    public static double rsi(List<Candle> candles, int period) {
        return BarSeriesCache.of(candles).rsi(period);
    }

    public static double macdHistogram(List<Candle> candles,
                                       int fast, int slow, int signal) {
        return BarSeriesCache.of(candles).macdHistogram(fast, slow, signal);
    }

    public static double atr(List<Candle> candles, int period) {
        return BarSeriesCache.of(candles).atr(period);
    }

    public static double adx(List<Candle> candles, int period) {
        return BarSeriesCache.of(candles).adx(period);
    }

    public static BarSeriesCache.BollingerValues bollinger(List<Candle> candles,
                                                            int period, double k) {
        return BarSeriesCache.of(candles).bollinger(period, k);
    }
}
