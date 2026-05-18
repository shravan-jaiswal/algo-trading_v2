package com.trading.signal;

import com.trading.model.Candle;
import com.trading.strategy.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Runs all registered strategies against a candle snapshot and publishes
 * actionable SignalEvents to the SignalBus.
 */
public final class SignalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SignalEvaluator.class);

    private final List<Strategy> strategies;
    private final SignalBus      bus;

    public SignalEvaluator(List<Strategy> strategies, SignalBus bus) {
        this.strategies = List.copyOf(strategies);
        this.bus        = bus;
    }

    /**
     * Evaluates only the strategies assigned to this symbol.
     * allowedStrategies: set of strategy names (uppercase) from watchlist.strategies column.
     */
    public void evaluate(String symbol, String token,
                         double currentPrice, List<Candle> candles,
                         Set<String> allowedStrategies) {
        if (candles == null || candles.isEmpty()) return;

        for (Strategy strategy : strategies) {
            if (!allowedStrategies.contains(strategy.getName().toUpperCase())) continue;
            if (candles.size() < strategy.getMinCandles()) continue;

            try {
                var ctx    = new Strategy.StrategyContext(symbol, token, candles);
                var signal = strategy.evaluate(ctx);
                if (signal == Strategy.Signal.NONE) continue;

                double sl = strategy.suggestStopLoss(ctx, signal);

                var event = new SignalEvent(
                        symbol, token, strategy.getName(),
                        signal, sl, currentPrice,
                        strategy.getName() + ":" + signal,
                        Instant.now()
                );

                log.info("Signal | {}", event);
                bus.publish(event);

            } catch (Exception e) {
                log.error("Strategy {} threw exception for {}: {}",
                        strategy.getName(), symbol, e.getMessage(), e);
            }
        }
    }
}
