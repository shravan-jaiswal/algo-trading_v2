package com.trading.strategy.scalping;

import com.trading.model.Candle;
import com.trading.strategy.Strategy.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MomentumScalpingSignalEngineTest {

    @Test
    void returnsLongForBullishBreakoutConfluence() {
        var engine = new MomentumScalpingSignalEngine(config(1.2));

        assertEquals(Signal.LONG, engine.evaluate(trendingCandles(true, 5000)).signal());
    }

    @Test
    void returnsShortForBearishBreakdownConfluence() {
        var engine = new MomentumScalpingSignalEngine(config(1.2));

        assertEquals(Signal.SHORT, engine.evaluate(trendingCandles(false, 5000)).signal());
    }

    @Test
    void rejectsSetupWithoutVolumeSpike() {
        var engine = new MomentumScalpingSignalEngine(config(1.2));

        assertEquals(Signal.NONE, engine.evaluate(trendingCandles(true, 1000)).signal());
    }

    private static MomentumScalpingConfig config(double volumeRatio) {
        return new MomentumScalpingConfig(3, 5, 3, 55, 45,
                3, 6, 3, 3, 1.5, 0,
                5, 3, 5, volumeRatio,
                LocalTime.of(9, 20), LocalTime.of(15, 0), false);
    }

    private static List<Candle> trendingCandles(boolean bullish, double latestVolume) {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 5, 19, 9, 15);
        for (int i = 0; i < 60; i++) {
            double close = bullish ? 100 + i : 200 - i;
            double open = bullish ? close - 0.5 : close + 0.5;
            candles.add(new Candle("T", "ONE_MINUTE", start.plusMinutes(i),
                    open, close + 0.25, close - 0.25, close, i == 59 ? latestVolume : 1000));
        }
        return candles;
    }
}
