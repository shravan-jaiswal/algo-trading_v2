package com.trading.indicator;

import com.trading.model.Candle;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a ta4j BarSeries exactly once from a candle snapshot.
 * All indicator calculations share this single series — eliminates the
 * O(n) rebuild-per-indicator overhead of the static IndicatorSupport methods.
 *
 * Usage: create one instance per evaluate() call, discard afterward.
 */
public final class BarSeriesCache {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BarSeries           series;
    private final ClosePriceIndicator close;

    private BarSeriesCache(List<Candle> candles) {
        // Build bar list first, then construct series — withBar() not available in ta4j 0.15
        List<Bar> bars = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            bars.add(new BaseBar(
                    parseDuration(c.getTimeframe()),
                    c.getTs().atZone(IST),
                    c.getOpen(), c.getHigh(), c.getLow(),
                    c.getClose(), c.getVolume(), 0.0));
        }
        this.series = new BaseBarSeriesBuilder()
                .withName("cache")
                .withBars(bars)
                .build();
        this.close = new ClosePriceIndicator(series);
    }

    public static BarSeriesCache of(List<Candle> candles) {
        return new BarSeriesCache(candles);
    }

    public int size() { return series.getBarCount(); }

    public double ema(int period) {
        if (series.getBarCount() < period) return 0;
        return new EMAIndicator(close, period)
                .getValue(series.getEndIndex()).doubleValue();
    }

    public double sma(int period) {
        if (series.getBarCount() < period) return 0;
        return new SMAIndicator(close, period)
                .getValue(series.getEndIndex()).doubleValue();
    }

    public double rsi(int period) {
        if (series.getBarCount() < period + 1) return 0;
        return new RSIIndicator(close, period)
                .getValue(series.getEndIndex()).doubleValue();
    }

    public double macdHistogram(int fast, int slow, int signal) {
        if (series.getBarCount() < slow + signal) return 0;
        var macd    = new MACDIndicator(close, fast, slow);
        var signalL = new EMAIndicator(macd, signal);
        int idx     = series.getEndIndex();
        return macd.getValue(idx).doubleValue()
             - signalL.getValue(idx).doubleValue();
    }

    public double atr(int period) {
        if (series.getBarCount() < period) return 0;
        return new ATRIndicator(series, period)
                .getValue(series.getEndIndex()).doubleValue();
    }

    public BollingerValues bollinger(int period, double k) {
        if (series.getBarCount() < period) return BollingerValues.EMPTY;
        var sma   = new SMAIndicator(close, period);
        var std   = new StandardDeviationIndicator(close, period);
        int idx   = series.getEndIndex();
        double mid   = sma.getValue(idx).doubleValue();
        double sigma = std.getValue(idx).doubleValue();
        return new BollingerValues(mid - k * sigma, mid, mid + k * sigma, k * sigma * 2);
    }

    public double adx(int period) {
        if (series.getBarCount() < period * 2) return 0;
        return new org.ta4j.core.indicators.adx.ADXIndicator(series, period)
                .getValue(series.getEndIndex()).doubleValue();
    }

    public record BollingerValues(double lower, double mid, double upper, double bandwidth) {
        static final BollingerValues EMPTY = new BollingerValues(0, 0, 0, 0);

        public boolean isSqueeze(double currentPrice, double threshold) {
            if (currentPrice <= 0 || mid <= 0) return false;
            return (bandwidth / currentPrice) < threshold;
        }

        public boolean isValid() { return mid > 0; }
    }

    private static Duration parseDuration(String tf) {
        if (tf == null) return Duration.ofMinutes(5);
        return switch (tf.toUpperCase()) {
            case "ONE_MINUTE"      -> Duration.ofMinutes(1);
            case "THREE_MINUTE"    -> Duration.ofMinutes(3);
            case "FIVE_MINUTE"     -> Duration.ofMinutes(5);
            case "FIFTEEN_MINUTE"  -> Duration.ofMinutes(15);
            case "THIRTY_MINUTE"   -> Duration.ofMinutes(30);
            case "ONE_HOUR"        -> Duration.ofHours(1);
            default                -> Duration.ofMinutes(5);
        };
    }
}
