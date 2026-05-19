package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.indicator.BarSeriesCache;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class RSIStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(RSIStrategy.class);

    private final int    period;
    private final double oversold;
    private final double overbought;
    private final InstrumentConfig instrumentConfig;

    public RSIStrategy(int period, double oversold, double overbought,
                       InstrumentConfig instrumentConfig) {
        this.period           = period;
        this.oversold         = oversold;
        this.overbought       = overbought;
        this.instrumentConfig = instrumentConfig;
    }

    public RSIStrategy() {
        this(
            AppConfig.getInt(   "strategy.rsi.period",     14),
            AppConfig.getDouble("strategy.rsi.oversold",   30),
            AppConfig.getDouble("strategy.rsi.overbought", 70),
            InstrumentConfig.EQUITY
        );
    }

    @Override
    public String getName() { return "RSI_" + period; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        if (candles.size() < period + 2) return Signal.NONE;

        BarSeriesCache cache = BarSeriesCache.of(candles);
        double rsiNow = cache.rsi(period);
        if (rsiNow <= 0) return Signal.NONE;

        // Previous bar RSI for crossover detection
        BarSeriesCache prev  = BarSeriesCache.of(candles.subList(0, candles.size() - 1));
        double rsiPrev = prev.rsi(period);

        boolean buySignal  = rsiPrev <= oversold  && rsiNow > oversold;
        boolean sellSignal = rsiPrev >= overbought && rsiNow < overbought;

        if (buySignal)  { log.debug("RSI LONG  | rsi:{}", rsiNow); return Signal.LONG; }
        if (sellSignal) { log.debug("RSI SHORT | rsi:{}", rsiNow); return Signal.SHORT; }
        return Signal.NONE;
    }

    @Override
    public int getMinCandles() { return period + 2; }

    @Override
    public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }
}
