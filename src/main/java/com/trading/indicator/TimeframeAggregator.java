package com.trading.indicator;

import com.trading.model.Candle;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimeframeAggregator {

    private TimeframeAggregator() {}

    private static final LocalTime SESSION_START = LocalTime.of(9, 15);

    /** Aggregates completed 5-minute candles into completed 15-minute candles. */
    public static List<Candle> toFifteenMinutes(List<Candle> candles5m) {
        return aggregateCompleted(candles5m, 15, "FIFTEEN_MINUTE");
    }

    /** Aggregates completed 5-minute candles into completed 30-minute candles. */
    public static List<Candle> toThirtyMinutes(List<Candle> candles5m) {
        return aggregateCompleted(candles5m, 30, "THIRTY_MINUTE");
    }

    /** Aggregates completed 1-minute candles into completed 5-minute candles. */
    public static List<Candle> toFiveMinutes(List<Candle> candles1m) {
        return aggregateCompleted(candles1m, 5, "FIVE_MINUTE");
    }

    /**
     * Aggregates only full buckets. Buckets are aligned from the 09:15 IST
     * market open, so 15-minute candles start at 09:15, 09:30, and so on.
     */
    public static List<Candle> aggregateCompleted(List<Candle> source,
                                                   int targetMinutes,
                                                   String timeframe) {
        if (source == null || source.isEmpty()) return List.of();
        int sourceMinutes = timeframeMinutes(source.get(0).getTimeframe());
        if (targetMinutes <= 0 || sourceMinutes <= 0 || targetMinutes % sourceMinutes != 0) {
            throw new IllegalArgumentException("Target timeframe must be a multiple of source timeframe");
        }
        int expectedBars = targetMinutes / sourceMinutes;

        List<Candle> result = new ArrayList<>(source.size() / expectedBars);
        Map<LocalDateTime, List<Candle>> buckets = new LinkedHashMap<>();
        for (Candle candle : source) {
            LocalDateTime alignedTs = alignTimestamp(candle.getTs(), targetMinutes);
            buckets.computeIfAbsent(alignedTs, k -> new ArrayList<>()).add(candle);
        }

        for (Map.Entry<LocalDateTime, List<Candle>> entry : buckets.entrySet()) {
            List<Candle> bucket = entry.getValue();
            if (bucket.size() != expectedBars) continue;

            Candle first = bucket.get(0);
            double open  = first.getOpen();
            double high  = bucket.stream().mapToDouble(Candle::getHigh).max().orElse(open);
            double low   = bucket.stream().mapToDouble(Candle::getLow).min().orElse(open);
            double close = bucket.get(bucket.size() - 1).getClose();
            double vol   = bucket.stream().mapToDouble(Candle::getVolume).sum();

            result.add(new Candle(first.getToken(), timeframe, entry.getKey(),
                    open, high, low, close, vol));
        }

        return result;
    }

    private static LocalDateTime alignTimestamp(LocalDateTime ts, int targetMinutes) {
        LocalDateTime sessionStart = ts.toLocalDate().atTime(SESSION_START);
        long minutesFromOpen = java.time.Duration.between(sessionStart, ts).toMinutes();
        long aligned = Math.floorDiv(minutesFromOpen, targetMinutes) * (long) targetMinutes;
        return sessionStart.plusMinutes(aligned);
    }

    private static int timeframeMinutes(String timeframe) {
        if (timeframe == null) return 0;
        return switch (timeframe.toUpperCase()) {
            case "ONE_MINUTE"     -> 1;
            case "THREE_MINUTE"   -> 3;
            case "FIVE_MINUTE"    -> 5;
            case "FIFTEEN_MINUTE" -> 15;
            case "THIRTY_MINUTE"  -> 30;
            default -> 0;
        };
    }
}
