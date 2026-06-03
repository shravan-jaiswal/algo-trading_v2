package com.trading.strategy.smc;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmcSignalEngineTest {

    @Test
    void detectsBullishAndBearishLiquiditySweeps() {
        List<Candle> bullish = List.of(
                candle(0, 100, 102, 99, 101),
                candle(1, 101, 103, 100, 102),
                candle(2, 101, 102, 98, 100));
        List<Candle> bearish = List.of(
                candle(0, 100, 102, 99, 101),
                candle(1, 101, 103, 100, 102),
                candle(2, 102, 104, 101, 102));

        assertNotNull(SmcSignalEngine.bullishSweep(bullish, 2, 2));
        assertNotNull(SmcSignalEngine.bearishSweep(bearish, 2, 2));
    }

    @Test
    void requiresCloseBackInsideLiquidityLevel() {
        List<Candle> candles = List.of(
                candle(0, 100, 102, 99, 101),
                candle(1, 101, 103, 100, 102),
                candle(2, 100, 101, 98, 98.5));

        assertNull(SmcSignalEngine.bullishSweep(candles, 2, 2));
    }

    @Test
    void detectsEqualLevelsAndFairValueGaps() {
        List<Candle> candles = List.of(
                candle(0, 100, 101, 99, 100),
                candle(1, 100, 102, 99.05, 101),
                candle(2, 103, 104, 102, 103));

        assertTrue(SmcSignalEngine.hasEqualLows(candles, 3, 0.1));
        assertTrue(SmcSignalEngine.bullishFvg(candles, 2, 2, 0.4));
        assertFalse(SmcSignalEngine.bearishFvg(candles, 2, 2, 0.4));
    }

    private static Candle candle(int minute, double open, double high, double low, double close) {
        return new Candle("T", "ONE_MINUTE", LocalDateTime.of(2026, 5, 19, 9, 20).plusMinutes(minute),
                open, high, low, close, 1000);
    }
}
