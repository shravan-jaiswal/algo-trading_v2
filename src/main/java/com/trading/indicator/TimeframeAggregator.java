package com.trading.indicator;

import com.trading.model.Candle;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimeframeAggregator {

    private TimeframeAggregator() {}

    /** Aggregates 5-minute candles into 15-minute candles. */
    public static List<Candle> toFifteenMinutes(List<Candle> candles5m) {
        return aggregate(candles5m, 3, "FIFTEEN_MINUTE");
    }

    /** Aggregates 5-minute candles into 30-minute candles. */
    public static List<Candle> toThirtyMinutes(List<Candle> candles5m) {
        return aggregate(candles5m, 6, "THIRTY_MINUTE");
    }

    /** Aggregates 1-minute candles into 5-minute candles. */
    public static List<Candle> toFiveMinutes(List<Candle> candles1m) {
        return aggregate(candles1m, 5, "FIVE_MINUTE");
    }

    private static List<Candle> aggregate(List<Candle> source, int factor, String timeframe) {
        if (source == null || source.isEmpty()) return List.of();

        List<Candle> result = new ArrayList<>(source.size() / factor + 1);
        Map<LocalDateTime, List<Candle>> buckets = new LinkedHashMap<>();
        for (Candle candle : source) {
            LocalDateTime alignedTs = alignTimestamp(candle.getTs(), timeframe);
            buckets.computeIfAbsent(alignedTs, k -> new ArrayList<>()).add(candle);
        }

        for (Map.Entry<LocalDateTime, List<Candle>> entry : buckets.entrySet()) {
            List<Candle> bucket = entry.getValue();
            if (bucket.isEmpty()) continue;

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

    private static LocalDateTime alignTimestamp(LocalDateTime ts, String tf) {
        return switch (tf) {
            case "FIFTEEN_MINUTE" -> {
                int min = ts.getMinute();
                int aligned = (min / 15) * 15;
                yield ts.withMinute(aligned).withSecond(0).withNano(0);
            }
            case "THIRTY_MINUTE" -> {
                int min = ts.getMinute();
                int aligned = (min / 30) * 30;
                yield ts.withMinute(aligned).withSecond(0).withNano(0);
            }
            case "FIVE_MINUTE" -> {
                int min = ts.getMinute();
                int aligned = (min / 5) * 5;
                yield ts.withMinute(aligned).withSecond(0).withNano(0);
            }
            default -> ts;
        };
    }
}
