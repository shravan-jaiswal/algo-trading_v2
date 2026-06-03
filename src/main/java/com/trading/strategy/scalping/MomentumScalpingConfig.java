package com.trading.strategy.scalping;

import com.trading.config.AppConfig;

import java.time.LocalTime;

public record MomentumScalpingConfig(
        int emaFast, int emaSlow,
        int rsiPeriod, double rsiBull, double rsiBear,
        int macdFast, int macdSlow, int macdSignal,
        int atrPeriod, double atrStopMultiplier, double minAtrPct,
        int openingRangeMinutes, int swingLookback,
        int volumeAvgPeriod, double volumeSpikeRatio,
        LocalTime entryStart, LocalTime entryCutoff,
        boolean require15mBias
) {
    public MomentumScalpingConfig {
        if (emaFast >= emaSlow) throw new IllegalArgumentException("emaFast must be < emaSlow");
        if (openingRangeMinutes < 1 || swingLookback < 2) throw new IllegalArgumentException("invalid breakout window");
        if (atrStopMultiplier <= 0 || minAtrPct < 0) throw new IllegalArgumentException("invalid ATR config");
    }

    public static MomentumScalpingConfig defaults() {
        return new MomentumScalpingConfig(9, 21, 14, 55, 45,
                12, 26, 9, 14, 1.5, 0.0005,
                15, 10, 20, 1.2,
                LocalTime.of(9, 20), LocalTime.of(15, 0), false);
    }

    public static MomentumScalpingConfig fromAppConfig() {
        MomentumScalpingConfig d = defaults();
        return new MomentumScalpingConfig(
                AppConfig.getInt("strategy.scalping.ema.fast", d.emaFast()),
                AppConfig.getInt("strategy.scalping.ema.slow", d.emaSlow()),
                AppConfig.getInt("strategy.scalping.rsi.period", d.rsiPeriod()),
                AppConfig.getDouble("strategy.scalping.rsi.bull", d.rsiBull()),
                AppConfig.getDouble("strategy.scalping.rsi.bear", d.rsiBear()),
                AppConfig.getInt("strategy.scalping.macd.fast", d.macdFast()),
                AppConfig.getInt("strategy.scalping.macd.slow", d.macdSlow()),
                AppConfig.getInt("strategy.scalping.macd.signal", d.macdSignal()),
                AppConfig.getInt("strategy.scalping.atr.period", d.atrPeriod()),
                AppConfig.getDouble("strategy.scalping.atr.stop.multiplier", d.atrStopMultiplier()),
                AppConfig.getDouble("strategy.scalping.atr.min.pct", d.minAtrPct()),
                AppConfig.getInt("strategy.scalping.opening.range.minutes", d.openingRangeMinutes()),
                AppConfig.getInt("strategy.scalping.swing.lookback", d.swingLookback()),
                AppConfig.getInt("strategy.scalping.volume.avg.period", d.volumeAvgPeriod()),
                AppConfig.getDouble("strategy.scalping.volume.spike.ratio", d.volumeSpikeRatio()),
                LocalTime.parse(AppConfig.get("strategy.scalping.entry.start", d.entryStart().toString())),
                LocalTime.parse(AppConfig.get("strategy.scalping.entry.cutoff", d.entryCutoff().toString())),
                AppConfig.getBool("strategy.scalping.require.15m.bias", d.require15mBias()));
    }
}
