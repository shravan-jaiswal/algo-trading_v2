package com.trading.signal;

import com.trading.model.Candle;
import com.trading.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SignalEvaluatorTest {

    @Test
    void publishesSignalAndKeepsAuditSnapshot() {
        SignalBus bus = new SignalBus();
        AtomicInteger published = new AtomicInteger();
        bus.subscribe(event -> published.incrementAndGet());

        Strategy strategy = new FixedStrategy(2, Strategy.Signal.LONG);
        SignalEvaluator evaluator = new SignalEvaluator(List.of(strategy), bus);

        evaluator.evaluate("ABC", "123", 102, candles(2), Set.of("FIXED"));

        assertEquals(1, published.get());
        var audit = evaluator.lastAudits().get("123|FIXED");
        assertNotNull(audit);
        assertEquals("LONG", audit.signal());
        assertEquals("signal_long", audit.reason());
    }

    @Test
    void auditsInsufficientCandlesWithoutPublishing() {
        SignalBus bus = new SignalBus();
        AtomicInteger published = new AtomicInteger();
        bus.subscribe(event -> published.incrementAndGet());

        Strategy strategy = new FixedStrategy(3, Strategy.Signal.LONG);
        SignalEvaluator evaluator = new SignalEvaluator(List.of(strategy), bus);

        evaluator.evaluate("ABC", "123", 101, candles(2), Set.of("FIXED"));

        assertEquals(0, published.get());
        var audit = evaluator.lastAudits().get("123|FIXED");
        assertNotNull(audit);
        assertEquals("NONE", audit.signal());
        assertTrue(audit.reason().startsWith("insufficient_candles"));
    }

    @Test
    void blocksSignalWhenTradeTypeDisallowsDirection() {
        System.setProperty("strategy.fixed.trade.type", "LONG");
        try {
            SignalBus bus = new SignalBus();
            AtomicInteger published = new AtomicInteger();
            bus.subscribe(event -> published.incrementAndGet());

            Strategy strategy = new FixedStrategy(2, Strategy.Signal.SHORT);
            SignalEvaluator evaluator = new SignalEvaluator(List.of(strategy), bus);

            evaluator.evaluate("ABC", "123", 102, candles(2), Set.of("FIXED"));

            assertEquals(0, published.get());
            var audit = evaluator.lastAudits().get("123|FIXED");
            assertNotNull(audit);
            assertEquals("NONE", audit.signal());
            assertEquals("trade_type_blocked:LONG:SHORT", audit.reason());
        } finally {
            System.clearProperty("strategy.fixed.trade.type");
        }
    }

    @Test
    void routesByPreferredTimeframeAndDeduplicatesClosedBar() {
        SignalBus bus = new SignalBus();
        AtomicInteger published = new AtomicInteger();
        bus.subscribe(event -> published.incrementAndGet());

        Strategy oneMinute = new TimedFixedStrategy("ONE", "ONE_MINUTE", Strategy.Signal.LONG);
        Strategy fiveMinute = new TimedFixedStrategy("FIVE", "FIVE_MINUTE", Strategy.Signal.LONG);
        SignalEvaluator evaluator = new SignalEvaluator(List.of(oneMinute, fiveMinute), bus);
        List<Candle> oneMinuteCandles = List.of(new Candle("123", "ONE_MINUTE",
                LocalDateTime.of(2026, 5, 19, 9, 20), 100, 101, 99, 100.5, 1000));

        evaluator.evaluate("ABC", "123", 100.5, oneMinuteCandles, Set.of("ONE", "FIVE"), "ONE_MINUTE");
        evaluator.evaluate("ABC", "123", 100.5, oneMinuteCandles, Set.of("ONE", "FIVE"), "ONE_MINUTE");

        assertEquals(1, published.get());
        assertNotNull(evaluator.lastAudits().get("123|ONE"));
        assertNull(evaluator.lastAudits().get("123|FIVE"));
    }

    private static List<Candle> candles(int count) {
        LocalDateTime start = LocalDateTime.of(2026, 5, 19, 9, 15);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Candle("123", "FIVE_MINUTE", start.plusMinutes(i * 5L),
                        100 + i, 101 + i, 99 + i, 100.5 + i, 1000))
                .toList();
    }

    private record FixedStrategy(int minCandles, Signal signal) implements Strategy {
        @Override public String getName() { return "FIXED"; }
        @Override public Signal evaluate(List<Candle> candles) { return signal; }
        @Override public int getMinCandles() { return minCandles; }
    }

    private record TimedFixedStrategy(String name, String timeframe, Signal signal) implements Strategy {
        @Override public String getName() { return name; }
        @Override public String preferredTimeframe() { return timeframe; }
        @Override public Signal evaluate(List<Candle> candles) { return signal; }
        @Override public int getMinCandles() { return 1; }
    }
}
