package com.trading.strategy.mics;

import com.trading.config.AppConfig;

import java.time.LocalTime;

public record MicsConfig(
        int       emaFast,
        int       emaSlow,
        int       rsiPeriod,
        double    rsiBullLow,
        double    rsiBullHigh,
        double    rsiBearLow,
        double    rsiBearHigh,
        int       macdShort,
        int       macdLong,
        int       macdSignal,
        int       bbPeriod,
        double    bbK,
        double    bbSqueezeThreshold,
        int       atrPeriod,
        double    atrStopMultiplier,
        double    hedgeOptionThreshold,
        double    hedgeSpreadThreshold,
        LocalTime entryStart,
        LocalTime entryCutoff,
        int       minConfluenceScore,
        boolean   useVwapGate,
        // ── v2 quality filters ──────────────────────────
        int       adxPeriod,         // ADX lookback (14)
        double    adxThreshold,      // min ADX on 15m to allow trading (22 = trending)
        double    volSpikeRatio,     // current bar volume / 20-bar avg must exceed this (1.2)
        int       volAvgPeriod       // bars for volume average (20)
) {
    public MicsConfig {
        if (emaFast >= emaSlow)
            throw new IllegalArgumentException("emaFast must be < emaSlow");
        if (minConfluenceScore < 1 || minConfluenceScore > 5)
            throw new IllegalArgumentException("minConfluenceScore must be 1..5");
        if (atrStopMultiplier <= 0)
            throw new IllegalArgumentException("atrStopMultiplier must be > 0");
        if (hedgeSpreadThreshold <= hedgeOptionThreshold)
            throw new IllegalArgumentException("spreadThreshold must be > optionThreshold");
    }

    public static MicsConfig defaults() {
        return new MicsConfig(
            10, 30,
            14, 52.0, 80.0, 20.0, 48.0,
            12, 26, 9,
            20, 2.0, 0.005,
            14, 1.5,
            0.02, 0.03,
            LocalTime.of(9, 30), LocalTime.of(14, 0),
            3, true,
            14, 22.0, 1.2, 20
        );
    }

    public static MicsConfig fromAppConfig() {
        return new MicsConfig(
            AppConfig.getInt(   "strategy.mics.ema.fast",              10),
            AppConfig.getInt(   "strategy.mics.ema.slow",              30),
            AppConfig.getInt(   "strategy.mics.rsi.period",            14),
            AppConfig.getDouble("strategy.mics.rsi.bull.low",          52),
            AppConfig.getDouble("strategy.mics.rsi.bull.high",         80),
            AppConfig.getDouble("strategy.mics.rsi.bear.low",          20),
            AppConfig.getDouble("strategy.mics.rsi.bear.high",         48),
            AppConfig.getInt(   "strategy.mics.macd.short",            12),
            AppConfig.getInt(   "strategy.mics.macd.long",             26),
            AppConfig.getInt(   "strategy.mics.macd.signal",            9),
            AppConfig.getInt(   "strategy.mics.bb.period",             20),
            AppConfig.getDouble("strategy.mics.bb.k",                  2.0),
            AppConfig.getDouble("strategy.mics.bb.squeeze.threshold",  0.005),
            AppConfig.getInt(   "strategy.mics.atr.period",            14),
            AppConfig.getDouble("strategy.mics.atr.stop.multiplier",   1.5),
            AppConfig.getDouble("strategy.mics.hedge.option.threshold", 0.02),
            AppConfig.getDouble("strategy.mics.hedge.spread.threshold", 0.03),
            LocalTime.parse(AppConfig.get("strategy.mics.entry.start",  "09:30")),
            LocalTime.parse(AppConfig.get("strategy.mics.entry.cutoff", "14:00")),
            AppConfig.getInt(   "strategy.mics.min.confluence.score",   3),
            AppConfig.getBool(  "strategy.mics.use.vwap.gate",          true),
            AppConfig.getInt(   "strategy.mics.adx.period",            14),
            AppConfig.getDouble("strategy.mics.adx.threshold",         22.0),
            AppConfig.getDouble("strategy.mics.vol.spike.ratio",        1.2),
            AppConfig.getInt(   "strategy.mics.vol.avg.period",        20)
        );
    }
}
