package com.trading.backtest;

import com.trading.model.Candle;
import com.trading.risk.RiskConfig;
import com.trading.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void intradayHoldingSquaresOffAtConfiguredSquareOffBar() {
        System.setProperty("strategy.always.holding.type", "INTRADY");
        try {
            Strategy strategy = new AlwaysLongStrategy();
            BacktestEngine engine = new BacktestEngine(strategy, RiskConfig.paperDefaults());

            BacktestEngine.BacktestResult result = engine.run(List.of(
                    candle("09:30", 100),
                    candle("10:00", 101),
                    candle("15:15", 102),
                    candle("19:25", 103)
            ));

            assertEquals(1, result.totalTrades());
            assertEquals(LocalDateTime.of(2026, 5, 19, 15, 15),
                    result.trades().get(0).exitTime());
            assertEquals("INTRADAY_EXIT", result.trades().get(0).exitReason());
        } finally {
            System.clearProperty("strategy.always.holding.type");
        }
    }

    @Test
    void deliveryHoldingDoesNotSquareOffAtConfiguredSquareOffBar() {
        System.setProperty("strategy.always.holding.type", "DELEVERY");
        try {
            Strategy strategy = new AlwaysLongStrategy();
            BacktestEngine engine = new BacktestEngine(strategy, RiskConfig.paperDefaults());

            BacktestEngine.BacktestResult result = engine.run(List.of(
                    candle("2026-05-19T09:30", 100),
                    candle("2026-05-19T10:00", 101),
                    candle("2026-05-19T15:15", 102),
                    candle("2026-05-20T09:30", 103)
            ));

            assertEquals(1, result.totalTrades());
            assertEquals("DELIVERY", result.holdingType());
            assertEquals(LocalDateTime.of(2026, 5, 20, 9, 30),
                    result.trades().get(0).exitTime());
            assertEquals("FINAL_BAR", result.trades().get(0).exitReason());
        } finally {
            System.clearProperty("strategy.always.holding.type");
        }
    }

    @Test
    void openTradeCanExitByStrategySignal() {
        Strategy strategy = new LongThenExitStrategy();
        BacktestEngine engine = new BacktestEngine(strategy, RiskConfig.paperDefaults(), false);

        BacktestEngine.BacktestResult result = engine.run(List.of(
                candle("09:30", 100),
                candle("09:35", 101),
                candle("09:40", 102)
        ));

        assertEquals(1, result.totalTrades());
        assertEquals("SIGNAL", result.trades().get(0).exitReason());
    }

    @Test
    void openTradeCanExitByTrailingStopLoss() {
        Strategy strategy = new AlwaysLongStrategy();
        RiskConfig noTakeProfit = new RiskConfig(
                500_000, 0.01, 0.02, 0.10,
                0.0, 0.01, 50, 10, 0.20);
        BacktestEngine engine = new BacktestEngine(strategy, noTakeProfit, false);

        BacktestEngine.BacktestResult result = engine.run(List.of(
                candle("09:30", 100),
                candle("09:35", 100),
                candle("09:40", 110),
                candle("09:45", 108)
        ));

        assertFalse(result.trades().isEmpty());
        assertEquals("TSL", result.trades().get(0).exitReason());
    }

    @Test
    void ignoresWeekendCandles() {
        Strategy strategy = new AlwaysLongStrategy();
        BacktestEngine engine = new BacktestEngine(strategy, RiskConfig.paperDefaults(), false);

        BacktestEngine.BacktestResult result = engine.run(List.of(
                candle("2026-05-16T09:30", 100),
                candle("2026-05-16T09:35", 101),
                candle("2026-05-16T09:40", 102)
        ));

        assertEquals(0, result.totalTrades());
    }

    @Test
    void subtractsConfiguredRoundTripCost() {
        try {
            Strategy strategy = new LongThenExitStrategy();
            List<Candle> candles = List.of(
                    candle("09:30", 100),
                    candle("09:35", 101),
                    candle("09:40", 102)
            );

            System.setProperty("backtest.fixed.cost.per.trade", "0");
            BacktestEngine grossEngine = new BacktestEngine(strategy, RiskConfig.paperDefaults(), false);
            BacktestEngine.BacktestResult gross = grossEngine.run(candles);

            System.setProperty("backtest.fixed.cost.per.trade", "50");
            BacktestEngine netEngine = new BacktestEngine(strategy, RiskConfig.paperDefaults(), false);
            BacktestEngine.BacktestResult net = netEngine.run(candles);

            assertEquals(1, net.totalTrades());
            assertEquals(gross.totalPnl() - 50, net.totalPnl(), 0.001);
        } finally {
            System.clearProperty("backtest.fixed.cost.per.trade");
        }
    }

    private static Candle candle(String time, double close) {
        String timestamp = time.contains("T") ? time : "2026-05-19T" + time;
        return new Candle("T", "FIVE_MINUTE", LocalDateTime.parse(timestamp),
                close, close + 1, close - 1, close, 1000);
    }

    private static class AlwaysLongStrategy implements Strategy {
        @Override public String getName() { return "ALWAYS"; }
        @Override public Signal evaluate(List<Candle> candles) { return Signal.LONG; }
        @Override public int getMinCandles() { return 1; }
        @Override public double suggestStopLoss(List<Candle> candles, Signal signal) {
            return candles.get(candles.size() - 1).getClose() - 1000;
        }
    }

    private static class LongThenExitStrategy implements Strategy {
        @Override public String getName() { return "LONG_THEN_EXIT"; }
        @Override public Signal evaluate(List<Candle> candles) {
            return candles.size() < 3 ? Signal.LONG : Signal.LONG_EXIT;
        }
        @Override public int getMinCandles() { return 1; }
        @Override public double suggestStopLoss(List<Candle> candles, Signal signal) {
            return candles.get(candles.size() - 1).getClose() - 1000;
        }
    }
}
