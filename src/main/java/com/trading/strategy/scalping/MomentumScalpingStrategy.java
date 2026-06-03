package com.trading.strategy.scalping;

import com.trading.model.Candle;
import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.Strategy;

import java.util.List;

public final class MomentumScalpingStrategy implements Strategy {

    private final MomentumScalpingSignalEngine engine;
    private final InstrumentConfig instrumentConfig;
    private volatile MomentumScalpingSignalEngine.Result lastResult =
            new MomentumScalpingSignalEngine.Result(Signal.NONE, -1, "init");

    public MomentumScalpingStrategy(MomentumScalpingConfig config, InstrumentConfig instrumentConfig) {
        this.engine = new MomentumScalpingSignalEngine(config);
        this.instrumentConfig = instrumentConfig;
    }

    @Override public String getName() { return "SCALPING"; }
    @Override public String preferredTimeframe() { return "ONE_MINUTE"; }
    @Override public int getMinCandles() { return engine.minRequiredCandles(); }
    @Override public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        lastResult = engine.evaluate(candles);
        return lastResult.signal();
    }

    @Override
    public double suggestStopLoss(List<Candle> candles, Signal signal) {
        return lastResult.stopLoss();
    }

    public MomentumScalpingSignalEngine.Result getLastResult() { return lastResult; }
}
