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
    private final SupertrendMode supertrendMode;
    private final boolean requireFreshSignal;
    private volatile String lastReason = "init";

    public record Diagnostics(
            int evaluatedBars,
            int rawLongSignals,
            int rawShortSignals,
            int aboveVwap,
            int belowVwap,
            int stBull,
            int stBear,
            int bullRsi,
            int bearRsi,
            int belowVwapAndStBear,
            int belowVwapAndBearRsi,
            int stBearAndBearRsi
    ) {}

    private record GateState(boolean aboveVwap, boolean stBull, boolean bullRsi, boolean bearRsi, double rsi) {
        boolean longSignal()  { return aboveVwap && stBull && bullRsi; }
        boolean shortSignal() { return !aboveVwap && !stBull && bearRsi; }
    }

    public enum SupertrendMode {
        LEGACY, REAL;

        public static SupertrendMode parse(String value) {
            if (value == null || value.isBlank()) return LEGACY;
            try {
                return SupertrendMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown VSRSI supertrend mode '{}', using LEGACY", value);
                return LEGACY;
            }
        }
    }

    public VwapSupertrendRsiStrategy(int atrPeriod, double atrMultiplier,
                                     int rsiPeriod,
                                     double rsiBullLow, double rsiBullHigh,
                                     double rsiBearLow, double rsiBearHigh,
                                     LocalTime entryStart, LocalTime entryCutoff,
                                     InstrumentConfig instrumentConfig) {
        this(atrPeriod, atrMultiplier, rsiPeriod,
                rsiBullLow, rsiBullHigh, rsiBearLow, rsiBearHigh,
                entryStart, entryCutoff, instrumentConfig, SupertrendMode.LEGACY);
    }

    public VwapSupertrendRsiStrategy(int atrPeriod, double atrMultiplier,
                                     int rsiPeriod,
                                     double rsiBullLow, double rsiBullHigh,
                                     double rsiBearLow, double rsiBearHigh,
                                     LocalTime entryStart, LocalTime entryCutoff,
                                     InstrumentConfig instrumentConfig,
                                     SupertrendMode supertrendMode) {
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
        this.supertrendMode  = supertrendMode == null ? SupertrendMode.LEGACY : supertrendMode;
        this.requireFreshSignal = AppConfig.getBool("strategy.vsrsi.require.fresh.signal", true);
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
                InstrumentConfig.EQUITY,
                SupertrendMode.parse(AppConfig.get("strategy.vsrsi.supertrend.mode", "LEGACY"))
        );
    }

    @Override
    public String getName() { return "VSRSI"; }

    @Override
    public Signal evaluate(List<Candle> candles) {
        if (candles.size() < getMinCandles()) {
            lastReason = "insufficient_candles:" + candles.size() + "/" + getMinCandles();
            return Signal.NONE;
        }

        Candle latest = candles.get(candles.size() - 1);
        LocalTime barTime = latest.getTs().toLocalTime();
        if (barTime.isBefore(entryStart) || barTime.isAfter(entryCutoff)) {
            lastReason = "outside_entry_window";
            return Signal.NONE;
        }

        BarSeriesCache cache = BarSeriesCache.of(candles);
        double close = latest.getClose();

        // 1. VWAP direction
        double vwap = VwapIndicator.current(candles);
        if (vwap <= 0) {
            lastReason = "vwap_invalid";
            return Signal.NONE;
        }
        GateState gate = gateState(candles, close, vwap, cache);
        if (gate == null) return Signal.NONE;

        if (gate.longSignal() && isFreshSignal(candles, true)) {
            lastReason = "signal_long";
            log.debug("VSRSI LONG | close:{} vwap:{} rsi:{}", close, vwap, gate.rsi());
            return Signal.LONG;
        }
        if (gate.shortSignal() && isFreshSignal(candles, false)) {
            lastReason = "signal_short";
            log.debug("VSRSI SHORT | close:{} vwap:{} rsi:{}", close, vwap, gate.rsi());
            return Signal.SHORT;
        }
        lastReason = "no_confluence:aboveVwap=" + gate.aboveVwap()
                + ",supertrendBull=" + gate.stBull()
                + ",supertrendMode=" + supertrendMode
                + ",bullRsi=" + gate.bullRsi()
                + ",bearRsi=" + gate.bearRsi();
        return Signal.NONE;
    }

    @Override
    public double suggestStopLoss(List<Candle> candles, Signal signal) {
        if (candles.size() < getMinCandles()) return -1;
        SupertrendIndicator.Result st = calculateSupertrend(candles);
        return st != null ? st.stopLine() : -1;
    }

    @Override
    public int getMinCandles() { return atrPeriod + rsiPeriod + 5; }

    @Override
    public InstrumentConfig getInstrumentConfig() { return instrumentConfig; }

    public String getLastReason() { return lastReason; }

    public SupertrendMode getSupertrendMode() { return supertrendMode; }

    public Diagnostics diagnose(List<Candle> candles) {
        int evaluated = 0;
        int rawLong = 0;
        int rawShort = 0;
        int above = 0;
        int below = 0;
        int bull = 0;
        int bear = 0;
        int bullRsiCount = 0;
        int bearRsiCount = 0;
        int belowAndBear = 0;
        int belowAndBearRsi = 0;
        int bearAndBearRsi = 0;

        for (int i = getMinCandles(); i < candles.size(); i++) {
            List<Candle> window = candles.subList(0, i + 1);
            Candle latest = window.get(window.size() - 1);
            LocalTime barTime = latest.getTs().toLocalTime();
            if (barTime.isBefore(entryStart) || barTime.isAfter(entryCutoff)) continue;

            BarSeriesCache cache = BarSeriesCache.of(window);
            double vwap = VwapIndicator.current(window);
            if (vwap <= 0) continue;

            GateState gate = gateState(window, latest.getClose(), vwap, cache);
            if (gate == null) continue;

            evaluated++;
            if (gate.aboveVwap()) above++; else below++;
            if (gate.stBull()) bull++; else bear++;
            if (gate.bullRsi()) bullRsiCount++;
            if (gate.bearRsi()) bearRsiCount++;
            if (!gate.aboveVwap() && !gate.stBull()) belowAndBear++;
            if (!gate.aboveVwap() && gate.bearRsi()) belowAndBearRsi++;
            if (!gate.stBull() && gate.bearRsi()) bearAndBearRsi++;
            if (gate.longSignal()) rawLong++;
            if (gate.shortSignal()) rawShort++;
        }

        return new Diagnostics(evaluated, rawLong, rawShort, above, below, bull, bear,
                bullRsiCount, bearRsiCount, belowAndBear, belowAndBearRsi, bearAndBearRsi);
    }

    private GateState gateState(List<Candle> candles, double close, double vwap, BarSeriesCache cache) {
        boolean aboveVwap = close > vwap;

        SupertrendIndicator.Result st = calculateSupertrend(candles);
        if (st == null) {
            lastReason = "supertrend_invalid";
            return null;
        }
        boolean stBull = st.bullish();

        double rsi = cache.rsi(rsiPeriod);
        if (rsi <= 0) {
            lastReason = "rsi_invalid";
            return null;
        }

        boolean bullRsi = rsi > rsiBullLow && rsi < rsiBullHigh;
        boolean bearRsi = rsi > rsiBearLow && rsi < rsiBearHigh;
        return new GateState(aboveVwap, stBull, bullRsi, bearRsi, rsi);
    }

    private boolean isFreshSignal(List<Candle> candles, boolean longSide) {
        if (!requireFreshSignal || candles.size() < getMinCandles() + 1) return true;
        Candle latest = candles.get(candles.size() - 1);
        Candle previous = candles.get(candles.size() - 2);
        if (!latest.getTs().toLocalDate().equals(previous.getTs().toLocalDate())) return true;

        List<Candle> priorWindow = candles.subList(0, candles.size() - 1);
        double priorVwap = VwapIndicator.current(priorWindow);
        if (priorVwap <= 0) return true;
        GateState prior = gateState(priorWindow, previous.getClose(), priorVwap,
                BarSeriesCache.of(priorWindow));
        if (prior == null) return true;
        return longSide ? !prior.longSignal() : !prior.shortSignal();
    }

    private SupertrendIndicator.Result calculateSupertrend(List<Candle> candles) {
        return supertrendMode == SupertrendMode.REAL
                ? SupertrendIndicator.calculate(candles, atrPeriod, atrMultiplier)
                : SupertrendIndicator.calculateLegacy(candles, atrPeriod, atrMultiplier);
    }
}
