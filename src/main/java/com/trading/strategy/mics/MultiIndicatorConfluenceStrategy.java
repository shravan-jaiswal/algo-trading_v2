package com.trading.strategy.mics;

import com.trading.model.Candle;
import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adapter: wraps MicsSignalEngine and implements Strategy interface
 * so it can be used in the standard signal evaluation pipeline.
 */
public class MultiIndicatorConfluenceStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(MultiIndicatorConfluenceStrategy.class);

    private final MicsSignalEngine  engine;
    private final MicsConfig        config;
    private final InstrumentConfig  instrumentConfig;

    // Last evaluated signal — carried to suggestStopLoss without re-evaluating
    private volatile StrategySignal lastSignal = StrategySignal.neutral("init");

    public MultiIndicatorConfluenceStrategy(MicsConfig config,
                                             InstrumentConfig instrumentConfig) {
        this.config           = config;
        this.engine           = new MicsSignalEngine(config);
        this.instrumentConfig = instrumentConfig;
    }

    public MultiIndicatorConfluenceStrategy() {
        this(MicsConfig.fromAppConfig(), InstrumentConfig.EQUITY);
    }

    @Override
    public String getName() { return "MICS"; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        lastSignal = engine.evaluate(candles);
        return switch (lastSignal) {
            case StrategySignal.BullSignal   b -> Signal.BUY;
            case StrategySignal.BearSignal   b -> Signal.SELL;
            case StrategySignal.NeutralSignal n -> Signal.NONE;
        };
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        Signal sig = evaluate(ctx != null ? ctx.candles() : List.of());
        if (sig == Signal.NONE && ctx != null) {
            String reason = lastSignal.reason();
            // suppress high-frequency expected reasons to avoid log spam
            if (!reason.startsWith("outside_entry") && !reason.startsWith("insufficient")) {
                log.info("MICS | {} | no-signal: {}", ctx.symbol(), reason);
            }
        }
        return sig;
    }

    @Override
    public double suggestStopLoss(List<Candle> candles, Signal signal) {
        return lastSignal.suggestedStopLoss();
    }

    @Override
    public int getMinCandles() { return engine.minRequiredCandles(); }

    @Override
    public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }

    public StrategySignal getLastSignal() { return lastSignal; }

    public HedgeMode getLastHedgeMode() { return lastSignal.hedgeMode(); }
}
