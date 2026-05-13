package com.trading.indicator;

import com.trading.model.Candle;

import java.util.List;

public final class CandlestickPatterns {

    private CandlestickPatterns() {}

    public static boolean isDoji(Candle c) {
        double body   = Math.abs(c.getClose() - c.getOpen());
        double range  = c.getHigh() - c.getLow();
        return range > 0 && body / range < 0.1;
    }

    public static boolean isHammer(Candle c) {
        double body       = Math.abs(c.getClose() - c.getOpen());
        double lowerShadow = Math.min(c.getOpen(), c.getClose()) - c.getLow();
        double upperShadow = c.getHigh() - Math.max(c.getOpen(), c.getClose());
        return body > 0 && lowerShadow >= body * 2 && upperShadow <= body * 0.5;
    }

    public static boolean isShootingStar(Candle c) {
        double body        = Math.abs(c.getClose() - c.getOpen());
        double upperShadow = c.getHigh() - Math.max(c.getOpen(), c.getClose());
        double lowerShadow = Math.min(c.getOpen(), c.getClose()) - c.getLow();
        return body > 0 && upperShadow >= body * 2 && lowerShadow <= body * 0.5;
    }

    public static boolean isBullishEngulfing(List<Candle> candles) {
        if (candles.size() < 2) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle curr = candles.get(candles.size() - 1);
        return prev.isBearish() && curr.isBullish()
                && curr.getOpen() < prev.getClose()
                && curr.getClose() > prev.getOpen();
    }

    public static boolean isBearishEngulfing(List<Candle> candles) {
        if (candles.size() < 2) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle curr = candles.get(candles.size() - 1);
        return prev.isBullish() && curr.isBearish()
                && curr.getOpen() > prev.getClose()
                && curr.getClose() < prev.getOpen();
    }

    public static boolean isMorningStar(List<Candle> candles) {
        if (candles.size() < 3) return false;
        Candle c1 = candles.get(candles.size() - 3);
        Candle c2 = candles.get(candles.size() - 2);
        Candle c3 = candles.get(candles.size() - 1);
        double c1Body = Math.abs(c1.getClose() - c1.getOpen());
        double c3Body = Math.abs(c3.getClose() - c3.getOpen());
        return c1.isBearish() && isDoji(c2) && c3.isBullish()
                && c3Body > c1Body * 0.5;
    }
}
