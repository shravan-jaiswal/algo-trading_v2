package com.trading.indicator;

import com.trading.model.Candle;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class SessionCandleFilter {

    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime SESSION_END   = LocalTime.of(15, 30);

    private SessionCandleFilter() {}

    /** Returns only candles from today's session. */
    public static List<Candle> todayOnly(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return List.of();
        LocalDate today = candles.get(candles.size() - 1).getTs().toLocalDate();
        return candles.stream()
                .filter(c -> c.getTs().toLocalDate().equals(today))
                .filter(c -> isWithinSession(c.getTs().toLocalTime()))
                .toList();
    }

    /** Returns candles within the market session window. */
    public static List<Candle> withinSession(List<Candle> candles) {
        if (candles == null) return List.of();
        return candles.stream()
                .filter(c -> isWithinSession(c.getTs().toLocalTime()))
                .toList();
    }

    private static boolean isWithinSession(LocalTime t) {
        return !t.isBefore(SESSION_START) && !t.isAfter(SESSION_END);
    }
}
