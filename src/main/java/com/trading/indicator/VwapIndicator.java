package com.trading.indicator;

import com.trading.model.Candle;

import java.time.LocalDate;
import java.util.List;

public final class VwapIndicator {

    private VwapIndicator() {}

    /**
     * Session VWAP: uses candles from today's market session only.
     * When volume data is available (feed.mode=QUOTE), uses true volume-weighted average.
     * Falls back to a simple average of typical prices when all today's candles have
     * zero volume (feed.mode=LTP), so VSRSI can still evaluate signals.
     * Returns 0 only if there are no today's candles at all.
     */
    public static double current(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0;

        LocalDate today = candles.get(candles.size() - 1).getTs().toLocalDate();

        double cumTpv   = 0;
        double cumVol   = 0;
        double sumTp    = 0;
        int    count    = 0;

        for (Candle c : candles) {
            if (!c.getTs().toLocalDate().equals(today)) continue;
            double tp = c.typicalPrice();
            double v  = c.getVolume();
            cumTpv   += tp * v;
            cumVol   += v;
            sumTp    += tp;
            count++;
        }

        if (cumVol > 0) return cumTpv / cumVol;             // true VWAP (QUOTE mode)
        return count > 0 ? sumTp / count : 0;               // simple-TP average (LTP mode fallback)
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
