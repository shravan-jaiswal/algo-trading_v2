package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.indicator.BarSeriesCache;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MACrossoverStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(MACrossoverStrategy.class);

    private final int    fast;
    private final int    slow;
    private final String maType;  // EMA | SMA
    private final InstrumentConfig instrumentConfig;

    public MACrossoverStrategy(int fast, int slow, String maType,
                                InstrumentConfig instrumentConfig) {
        if (fast >= slow) throw new IllegalArgumentException("fast must be < slow");
        this.fast             = fast;
        this.slow             = slow;
        this.maType           = maType;
        this.instrumentConfig = instrumentConfig;
    }

    public MACrossoverStrategy() {
        this(
            AppConfig.getInt("strategy.ma.fast", 10),
            AppConfig.getInt("strategy.ma.slow", 30),
            AppConfig.get("strategy.ma.type", "EMA"),
            InstrumentConfig.EQUITY
        );
    }

    @Override
    public String getName() { return "MA_CROSSOVER_" + maType + "_" + fast + "_" + slow; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        if (candles.size() < slow + 2) return Signal.NONE;

        BarSeriesCache cache = BarSeriesCache.of(candles);

        double fastNow  = ma(cache, fast);
        double slowNow  = ma(cache, slow);

        // Use n-1 candles to get previous bar values
        BarSeriesCache prev = BarSeriesCache.of(candles.subList(0, candles.size() - 1));
        double fastPrev = ma(prev, fast);
        double slowPrev = ma(prev, slow);

        if (fastNow <= 0 || slowNow <= 0) return Signal.NONE;

        boolean bullCross = fastPrev <= slowPrev && fastNow > slowNow;
        boolean bearCross = fastPrev >= slowPrev && fastNow < slowNow;

        if (bullCross) {
            log.debug("MA BULL cross | fast:{} > slow:{}", fastNow, slowNow);
            return Signal.LONG;
        }
        if (bearCross) {
            log.debug("MA BEAR cross | fast:{} < slow:{}", fastNow, slowNow);
            return Signal.SHORT;
        }
        return Signal.NONE;
    }

    @Override
    public int getMinCandles() { return slow + 2; }

    @Override
    public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }

    private double ma(BarSeriesCache cache, int period) {
        return "SMA".equalsIgnoreCase(maType) ? cache.sma(period) : cache.ema(period);
    }
}
