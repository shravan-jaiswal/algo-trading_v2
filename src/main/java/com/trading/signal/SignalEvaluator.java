package com.trading.signal;

import com.trading.data.StrategySignalAuditRepository;
import com.trading.indicator.BarSeriesCache;
import com.trading.indicator.SupertrendIndicator;
import com.trading.indicator.TimeframeAggregator;
import com.trading.indicator.VwapIndicator;
import com.trading.model.Candle;
import com.trading.model.StrategySignalAudit;
import com.trading.strategy.Strategy;
import com.trading.strategy.TradeType;
import com.trading.strategy.VwapSupertrendRsiStrategy;
import com.trading.strategy.mics.MultiIndicatorConfluenceStrategy;
import com.trading.strategy.mics.StrategySignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs all registered strategies against a candle snapshot and publishes
 * actionable SignalEvents to the SignalBus.
 */
public final class SignalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SignalEvaluator.class);

    private final List<Strategy> strategies;
    private final SignalBus      bus;
    private final StrategySignalAuditRepository auditRepo;
    private final Map<String, StrategySignalAudit> lastAudits = new ConcurrentHashMap<>();

    public SignalEvaluator(List<Strategy> strategies, SignalBus bus) {
        this(strategies, bus, null);
    }

    public SignalEvaluator(List<Strategy> strategies, SignalBus bus,
                           StrategySignalAuditRepository auditRepo) {
        this.strategies = List.copyOf(strategies);
        this.bus        = bus;
        this.auditRepo  = auditRepo;
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
            if (allowedStrategies == null
                    || !allowedStrategies.contains(strategy.getName().toUpperCase())) continue;
            if (candles.size() < strategy.getMinCandles()) {
                audit(symbol, token, currentPrice, candles, strategy, Strategy.Signal.NONE,
                        "insufficient_candles:" + candles.size() + "/" + strategy.getMinCandles());
                continue;
            }

            try {
                var ctx    = new Strategy.StrategyContext(symbol, token, candles);
                var signal = strategy.evaluate(ctx);
                TradeType tradeType = TradeType.fromConfig(strategy);
                if (!tradeType.allows(signal)) {
                    audit(symbol, token, currentPrice, candles, strategy, Strategy.Signal.NONE,
                            "trade_type_blocked:" + tradeType + ":" + signal);
                    continue;
                }
                String reason = reasonFor(strategy, signal);
                audit(symbol, token, currentPrice, candles, strategy, signal, reason);
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
                audit(symbol, token, currentPrice, candles, strategy, Strategy.Signal.NONE,
                        "exception:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }
    }

    public Map<String, StrategySignalAudit> lastAudits() {
        return Map.copyOf(lastAudits);
    }

    private void audit(String symbol, String token, double currentPrice, List<Candle> candles,
                       Strategy strategy, Strategy.Signal signal, String reason) {
        try {
            Metrics m = Metrics.from(candles);
            Score s = scoreFor(strategy);
            Candle latest = candles.get(candles.size() - 1);
            StrategySignalAudit row = new StrategySignalAudit(
                    Instant.now(), token, symbol, strategy.getName(), latest.getTs(), candles.size(),
                    signal.name(), reason, currentPrice, latest.getClose(),
                    m.rsi(), m.vwap(), m.supertrend(), m.supertrendBullish(),
                    m.adx(), m.volumeRatio(), s.bullScore(), s.bearScore());

            lastAudits.put(token + "|" + strategy.getName(), row);
            if (auditRepo != null) auditRepo.save(row);
        } catch (Exception e) {
            log.warn("Could not create signal audit for {} {}: {}",
                    symbol, strategy.getName(), e.getMessage());
        }
    }

    private static String reasonFor(Strategy strategy, Strategy.Signal signal) {
        if (signal != Strategy.Signal.NONE) return "signal_" + signal.name().toLowerCase();
        if (strategy instanceof MultiIndicatorConfluenceStrategy mics) {
            return mics.getLastSignal().reason();
        }
        if (strategy instanceof VwapSupertrendRsiStrategy vsrsi) {
            return vsrsi.getLastReason();
        }
        return "no_signal";
    }

    private static Score scoreFor(Strategy strategy) {
        if (strategy instanceof MultiIndicatorConfluenceStrategy mics) {
            StrategySignal sig = mics.getLastSignal();
            return switch (sig) {
                case StrategySignal.BullSignal b -> new Score(b.confluenceScore(), 0);
                case StrategySignal.BearSignal b -> new Score(0, b.confluenceScore());
                case StrategySignal.NeutralSignal n -> parseNeutralScore(n.reason());
            };
        }
        return new Score(0, 0);
    }

    private static Score parseNeutralScore(String reason) {
        if (reason == null || !reason.startsWith("no_confluence:")) return new Score(0, 0);
        int bull = parseScore(reason, "bull=");
        int bear = parseScore(reason, "bear=");
        return new Score(bull, bear);
    }

    private static int parseScore(String reason, String key) {
        int idx = reason.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < reason.length() && Character.isDigit(reason.charAt(end))) end++;
        try {
            return Integer.parseInt(reason.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Score(int bullScore, int bearScore) {}

    private record Metrics(double rsi, double vwap, double supertrend,
                           Boolean supertrendBullish, double adx, double volumeRatio) {
        static Metrics from(List<Candle> candles) {
            double rsi = 0, vwap = 0, supertrend = 0, adx = 0, volumeRatio = 0;
            Boolean stBull = null;

            try {
                BarSeriesCache cache = BarSeriesCache.of(candles);
                rsi = cache.rsi(14);
                volumeRatio = cache.volumeRatio(20);
            } catch (Exception ignored) {}

            try { vwap = VwapIndicator.current(candles); } catch (Exception ignored) {}

            try {
                SupertrendIndicator.Result st = SupertrendIndicator.calculate(candles, 10, 3.0);
                if (st != null) {
                    supertrend = st.stopLine();
                    stBull = st.bullish();
                }
            } catch (Exception ignored) {}

            try {
                List<Candle> candles15m = TimeframeAggregator.toFifteenMinutes(candles);
                if (candles15m.size() >= 28) adx = BarSeriesCache.of(candles15m).adx(14);
            } catch (Exception ignored) {}

            return new Metrics(rsi, vwap, supertrend, stBull, adx, volumeRatio);
        }
    }
}
