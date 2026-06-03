package com.trading.strategy.smc;

import com.trading.model.Candle;
import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.Strategy;

import java.util.List;

public final class SmcLiquiditySweepStrategy implements Strategy {

    private final SmcSignalEngine engine;
    private final InstrumentConfig instrumentConfig;
    private volatile SmcSignalEngine.Result lastResult = new SmcSignalEngine.Result(Signal.NONE, -1, "init");

    public SmcLiquiditySweepStrategy(SmcConfig config, InstrumentConfig instrumentConfig) {
        this.engine = new SmcSignalEngine(config);
        this.instrumentConfig = instrumentConfig;
    }

    @Override public String getName() { return "SMC"; }
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

    public SmcSignalEngine.Result getLastResult() { return lastResult; }
}
