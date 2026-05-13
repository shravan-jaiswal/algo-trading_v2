package com.trading.indicator;

import com.trading.model.Candle;

import java.time.LocalDate;
import java.util.List;

public final class VwapIndicator {

    private VwapIndicator() {}

    /**
     * Session VWAP: uses candles from today's market session only.
     * Returns 0 if not enough data.
     */
    public static double current(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0;

        LocalDate today = candles.get(candles.size() - 1).getTs().toLocalDate();

        double cumTpv   = 0;
        double cumVol   = 0;

        for (Candle c : candles) {
            if (!c.getTs().toLocalDate().equals(today)) continue;
            double tp = c.typicalPrice();
            double v  = c.getVolume();
            cumTpv   += tp * v;
            cumVol   += v;
        }

        return cumVol > 0 ? cumTpv / cumVol : 0;
    }

    /**
     * Returns true if price is above VWAP with a minimum margin.
     */
    public static boolean isAbove(List<Candle> candles, double margin) {
        if (candles.isEmpty()) return false;
        double vwap  = current(candles);
        double close = candles.get(candles.size() - 1).getClose();
        return vwap > 0 && close > vwap * (1 + margin);
    }

    public static boolean isBelow(List<Candle> candles, double margin) {
        if (candles.isEmpty()) return false;
        double vwap  = current(candles);
        double close = candles.get(candles.size() - 1).getClose();
        return vwap > 0 && close < vwap * (1 - margin);
    }
}
