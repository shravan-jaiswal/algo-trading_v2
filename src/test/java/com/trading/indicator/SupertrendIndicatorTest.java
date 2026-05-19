package com.trading.indicator;

import com.trading.model.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupertrendIndicatorTest {

    @Test
    void calculatesBullishTrailingStopForRisingSeries() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime ts = LocalDateTime.of(2026, 5, 19, 9, 15);
        for (int i = 0; i < 40; i++) {
            double close = 100 + i;
            candles.add(new Candle("T", "FIVE_MINUTE", ts.plusMinutes(i * 5L),
                    close - 0.5, close + 1, close - 1, close, 1000));
        }

        SupertrendIndicator.Result result = SupertrendIndicator.calculate(candles, 10, 3);

        assertNotNull(result);
        assertTrue(result.bullish());
        assertTrue(result.stopLine() < candles.get(candles.size() - 1).getClose());
    }

    @Test
    void legacyModeKeepsLooseBandProxyAvailable() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime ts = LocalDateTime.of(2026, 5, 19, 9, 15);
        for (int i = 0; i < 20; i++) {
            double close = 100 + Math.sin(i);
            candles.add(new Candle("T", "FIVE_MINUTE", ts.plusMinutes(i * 5L),
                    close - 0.5, close + 1, close - 1, close, 1000));
        }

        SupertrendIndicator.Result result = SupertrendIndicator.calculateLegacy(candles, 10, 3);

        assertNotNull(result);
        assertTrue(result.bullish());
    }

    @Test
    void legacyModeCanTurnBearishForFallingCandles() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime ts = LocalDateTime.of(2026, 5, 19, 9, 15);
        for (int i = 0; i < 30; i++) {
            double close = 120 - i;
            candles.add(new Candle("T", "FIVE_MINUTE", ts.plusMinutes(i * 5L),
                    close + 0.5, close + 1, close - 1, close, 1000));
        }

        SupertrendIndicator.Result legacy = SupertrendIndicator.calculateLegacy(candles, 10, 3);
        SupertrendIndicator.Result real = SupertrendIndicator.calculate(candles, 10, 3);

        assertNotNull(legacy);
        assertNotNull(real);
        assertFalse(legacy.bullish(), "Legacy direction proxy must allow short-side signals");
        assertFalse(real.bullish(), "Real trailing Supertrend should detect the downtrend");
    }
}
