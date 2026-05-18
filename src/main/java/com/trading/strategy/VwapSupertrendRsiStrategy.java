package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.indicator.BarSeriesCache;
import com.trading.indicator.SupertrendIndicator;
import com.trading.indicator.VwapIndicator;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.List;

public class VwapSupertrendRsiStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(VwapSupertrendRsiStrategy.class);

    private final int    atrPeriod;
    private final double atrMultiplier;
    private final int    rsiPeriod;
    private final double rsiBullLow;
    private final double rsiBullHigh;
    private final double rsiBearLow;
    private final double rsiBearHigh;
    private final LocalTime entryStart;
    private final LocalTime entryCutoff;
    private final InstrumentConfig instrumentConfig;

    public VwapSupertrendRsiStrategy(int atrPeriod, double atrMultiplier,
                                      int rsiPeriod,
                                      double rsiBullLow, double rsiBullHigh,
                                      double rsiBearLow, double rsiBearHigh,
                                      LocalTime entryStart, LocalTime entryCutoff,
                                      InstrumentConfig instrumentConfig) {
        this.atrPeriod       = atrPeriod;
        this.atrMultiplier   = atrMultiplier;
        this.rsiPeriod       = rsiPeriod;
        this.rsiBullLow      = rsiBullLow;
        this.rsiBullHigh     = rsiBullHigh;
        this.rsiBearLow      = rsiBearLow;
        this.rsiBearHigh     = rsiBearHigh;
        this.entryStart      = entryStart;
        this.entryCutoff     = entryCutoff;
        this.instrumentConfig = instrumentConfig;
    }

    public VwapSupertrendRsiStrategy() {
        this(
            AppConfig.getInt(   "strategy.vsrsi.atr.period",       10),
            AppConfig.getDouble("strategy.vsrsi.atr.multiplier",   3.0),
            AppConfig.getInt(   "strategy.vsrsi.rsi.period",       14),
            AppConfig.getDouble("strategy.vsrsi.rsi.bull.low",     40),
            AppConfig.getDouble("strategy.vsrsi.rsi.bull.high",    70),
            AppConfig.getDouble("strategy.vsrsi.rsi.bear.low",     30),
            AppConfig.getDouble("strategy.vsrsi.rsi.bear.high",    60),
            LocalTime.parse(AppConfig.get("strategy.vsrsi.entry.start",  "09:30")),
            LocalTime.parse(AppConfig.get("strategy.vsrsi.entry.cutoff", "14:30")),
            InstrumentConfig.EQUITY
        );
    }

    @Override
    public String getName() { return "VSRSI"; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        if (candles.size() < getMinCandles()) return Signal.NONE;

        Candle latest = candles.get(candles.size() - 1);
        LocalTime barTime = latest.getTs().toLocalTime();
        if (barTime.isBefore(entryStart) || barTime.isAfter(entryCutoff)) return Signal.NONE;

        BarSeriesCache cache = BarSeriesCache.of(candles);
        double close = latest.getClose();

        // 1. VWAP direction
        double vwap = VwapIndicator.current(candles);
        if (vwap <= 0) return Signal.NONE;
        boolean aboveVwap = close > vwap;

        // 2. Supertrend direction
        SupertrendIndicator.Result st = SupertrendIndicator.calculate(candles, atrPeriod, atrMultiplier);
        if (st == null) return Signal.NONE;
        boolean stBull = st.bullish();

        // 3. RSI confirmation
        double rsi = cache.rsi(rsiPeriod);
        if (rsi <= 0) return Signal.NONE;

        boolean bullRsi = rsi > rsiBullLow && rsi < rsiBullHigh;
        boolean bearRsi = rsi > rsiBearLow && rsi < rsiBearHigh;

        if (aboveVwap && stBull && bullRsi) {
            log.debug("VSRSI BUY | close:{} vwap:{} rsi:{}", close, vwap, rsi);
            return Signal.BUY;
        }
        if (!aboveVwap && !stBull && bearRsi) {
            log.debug("VSRSI SELL | close:{} vwap:{} rsi:{}", close, vwap, rsi);
            return Signal.SELL;
        }
        return Signal.NONE;
    }

    @Override
    public double suggestStopLoss(List<Candle> candles, Signal signal) {
        if (candles.size() < getMinCandles()) return -1;
        SupertrendIndicator.Result st = SupertrendIndicator.calculate(candles, atrPeriod, atrMultiplier);
        return st != null ? st.stopLine() : -1;
    }

    @Override
    public int getMinCandles() { return atrPeriod + rsiPeriod + 5; }

    @Override
    public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }
}
