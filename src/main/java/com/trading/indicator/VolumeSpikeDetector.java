package com.trading.indicator;

import com.trading.model.Candle;

import java.util.List;

public final class VolumeSpikeDetector {

    private VolumeSpikeDetector() {}

    /**
     * Returns true if the last candle's volume is a spike relative to the
     * average of the previous {@code lookback} candles.
     */
    public static boolean isSpike(List<Candle> candles, int lookback, double multiplier) {
        if (candles.size() < lookback + 1) return false;

        int end = candles.size() - 1;
        Candle latest = candles.get(end);

        double avgVol = 0;
        for (int i = end - lookback; i < end; i++) {
            avgVol += candles.get(i).getVolume();
        }
        avgVol /= lookback;

        return avgVol > 0 && latest.getVolume() > avgVol * multiplier;
    }

    /** Average volume over the last {@code n} closed candles (excludes current). */
    public static double averageVolume(List<Candle> candles, int n) {
        if (candles.size() < n + 1) return 0;
        int end = candles.size() - 1;
        double sum = 0;
        for (int i = end - n; i < end; i++) {
            sum += candles.get(i).getVolume();
        }
        return sum / n;
    }
}
