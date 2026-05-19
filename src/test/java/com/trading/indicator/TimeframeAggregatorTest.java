package com.trading.indicator;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeframeAggregatorTest {

    @Test
    void aggregatesByClockBucketNotByRowPosition() {
        List<Candle> candles = List.of(
                candle("T", "FIVE_MINUTE", 13, 5, 100),
                candle("T", "FIVE_MINUTE", 13, 10, 101),
                candle("T", "FIVE_MINUTE", 13, 15, 102),
                candle("T", "FIVE_MINUTE", 13, 20, 103));

        List<Candle> aggregated = TimeframeAggregator.toFifteenMinutes(candles);

        assertEquals(2, aggregated.size());
        assertEquals(LocalDateTime.of(2026, 5, 19, 13, 0), aggregated.get(0).getTs());
        assertEquals(99.5, aggregated.get(0).getOpen());
        assertEquals(101, aggregated.get(0).getClose());
        assertEquals(LocalDateTime.of(2026, 5, 19, 13, 15), aggregated.get(1).getTs());
        assertEquals(101.5, aggregated.get(1).getOpen());
        assertEquals(103, aggregated.get(1).getClose());
    }

    private static Candle candle(String token, String timeframe, int hour, int minute, double close) {
        return new Candle(token, timeframe, LocalDateTime.of(2026, 5, 19, hour, minute),
                close - 0.5, close + 1, close - 1, close, 1000);
    }
}
