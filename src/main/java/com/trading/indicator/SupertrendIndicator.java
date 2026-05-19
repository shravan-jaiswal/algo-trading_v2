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

        double[] atr = atr(candles, atrPeriod);
        int start = firstValidAtrIndex(atr);
        if (start < 0) return null;

        Candle startBar = candles.get(start);
        double hl2 = (startBar.getHigh() + startBar.getLow()) / 2.0;
        double finalUpper = hl2 + multiplier * atr[start];
        double finalLower = hl2 - multiplier * atr[start];
        boolean bullish = startBar.getClose() >= finalLower;
        double supertrend = bullish ? finalLower : finalUpper;

        for (int i = start + 1; i < candles.size(); i++) {
            Candle bar = candles.get(i);
            Candle prev = candles.get(i - 1);

            hl2 = (bar.getHigh() + bar.getLow()) / 2.0;
            double basicUpper = hl2 + multiplier * atr[i];
            double basicLower = hl2 - multiplier * atr[i];

            finalUpper = (basicUpper < finalUpper || prev.getClose() > finalUpper)
                    ? basicUpper : finalUpper;
            finalLower = (basicLower > finalLower || prev.getClose() < finalLower)
                    ? basicLower : finalLower;

            if (bullish) {
                bullish = bar.getClose() >= finalLower;
            } else {
                bullish = bar.getClose() > finalUpper;
            }
            supertrend = bullish ? finalLower : finalUpper;
        }

        return new Result(supertrend, bullish);
    }

    /**
     * Legacy VSRSI behavior: uses current ATR bands for stop placement and a
     * simple candle-direction proxy for trend. This keeps the loose, non-trailing
     * band behavior while allowing both bullish and bearish signals.
     */
    public static Result calculateLegacy(List<Candle> candles, int atrPeriod, double multiplier) {
        if (candles.size() < atrPeriod + 2) return null;

        double[] atr = atr(candles, atrPeriod);
        double latestAtr = atr[candles.size() - 1];
        if (latestAtr <= 0) return null;

        Candle latest = candles.get(candles.size() - 1);
        double hl2 = (latest.getHigh() + latest.getLow()) / 2.0;
        double upperBand = hl2 + multiplier * latestAtr;
        double lowerBand = hl2 - multiplier * latestAtr;
        boolean bullish = latest.isBullish() || (!latest.isBearish() && latest.getClose() >= hl2);
        return new Result(bullish ? lowerBand : upperBand, bullish);
    }

    private static double[] atr(List<Candle> candles, int period) {
        double[] atr = new double[candles.size()];
        double sumTr = 0;

        for (int i = 1; i < candles.size(); i++) {
            double tr = trueRange(candles.get(i), candles.get(i - 1));
            if (i <= period) {
                sumTr += tr;
                if (i == period) atr[i] = sumTr / period;
            } else {
                atr[i] = (atr[i - 1] * (period - 1) + tr) / period;
            }
        }

        return atr;
    }

    private static double trueRange(Candle bar, Candle prev) {
        double highLow = bar.getHigh() - bar.getLow();
        double highClose = Math.abs(bar.getHigh() - prev.getClose());
        double lowClose = Math.abs(bar.getLow() - prev.getClose());
        return Math.max(highLow, Math.max(highClose, lowClose));
    }

    private static int firstValidAtrIndex(double[] atr) {
        for (int i = 0; i < atr.length; i++) {
            if (atr[i] > 0) return i;
        }
        return -1;
    }
}
