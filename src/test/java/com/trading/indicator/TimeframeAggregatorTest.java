package com.trading.indicator;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeframeAggregatorTest {

    @Test
    void aggregatesFullBucketsAlignedFromMarketOpen() {
        List<Candle> candles = List.of(
                candle("T", "FIVE_MINUTE", 9, 15, 100),
                candle("T", "FIVE_MINUTE", 9, 20, 101),
                candle("T", "FIVE_MINUTE", 9, 25, 102),
                candle("T", "FIVE_MINUTE", 9, 30, 103));

        List<Candle> aggregated = TimeframeAggregator.toFifteenMinutes(candles);

        assertEquals(1, aggregated.size());
        assertEquals(LocalDateTime.of(2026, 5, 19, 9, 15), aggregated.get(0).getTs());
        assertEquals(99.5, aggregated.get(0).getOpen());
        assertEquals(102, aggregated.get(0).getClose());
    }

    @Test
    void aggregatesOneMinuteCandlesIntoFiveMinuteOhlcv() {
        List<Candle> candles = List.of(
                candle("T", "ONE_MINUTE", 9, 15, 100),
                candle("T", "ONE_MINUTE", 9, 16, 101),
                candle("T", "ONE_MINUTE", 9, 17, 102),
                candle("T", "ONE_MINUTE", 9, 18, 103),
                candle("T", "ONE_MINUTE", 9, 19, 104),
                candle("T", "ONE_MINUTE", 9, 20, 105));

        List<Candle> aggregated = TimeframeAggregator.toFiveMinutes(candles);

        assertEquals(1, aggregated.size());
        Candle bar = aggregated.get(0);
        assertEquals(LocalDateTime.of(2026, 5, 19, 9, 15), bar.getTs());
        assertEquals(99.5, bar.getOpen());
        assertEquals(105, bar.getHigh());
        assertEquals(99, bar.getLow());
        assertEquals(104, bar.getClose());
        assertEquals(5000, bar.getVolume());
    }

    private static Candle candle(String token, String timeframe, int hour, int minute, double close) {
        return new Candle(token, timeframe, LocalDateTime.of(2026, 5, 19, hour, minute),
                close - 0.5, close + 1, close - 1, close, 1000);
    }
}
