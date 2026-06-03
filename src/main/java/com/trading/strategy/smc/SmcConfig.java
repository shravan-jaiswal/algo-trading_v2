package com.trading.strategy.smc;

import com.trading.config.AppConfig;

import java.time.LocalTime;

public record SmcConfig(
        int atrPeriod, double atrStopMultiplier,
        int liquidityLookback, int sweepRecentBars,
        int swingLookback, double equalLevelAtrTolerance,
        double minFvgAtrPct,
        int volumeAvgPeriod, double volumeSpikeRatio,
        LocalTime entryStart, LocalTime entryCutoff
) {
    public SmcConfig {
        if (liquidityLookback < 3 || sweepRecentBars < 1 || swingLookback < 2)
            throw new IllegalArgumentException("invalid SMC lookback");
        if (atrStopMultiplier <= 0 || equalLevelAtrTolerance < 0 || minFvgAtrPct < 0)
            throw new IllegalArgumentException("invalid SMC threshold");
    }

    public static SmcConfig defaults() {
        return new SmcConfig(14, 1.5, 20, 5, 10,
                0.15, 0.10, 20, 1.2,
                LocalTime.of(9, 20), LocalTime.of(15, 0));
    }

    public static SmcConfig fromAppConfig() {
        SmcConfig d = defaults();
        return new SmcConfig(
                AppConfig.getInt("strategy.smc.atr.period", d.atrPeriod()),
                AppConfig.getDouble("strategy.smc.atr.stop.multiplier", d.atrStopMultiplier()),
                AppConfig.getInt("strategy.smc.liquidity.lookback", d.liquidityLookback()),
                AppConfig.getInt("strategy.smc.sweep.recent.bars", d.sweepRecentBars()),
                AppConfig.getInt("strategy.smc.swing.lookback", d.swingLookback()),
                AppConfig.getDouble("strategy.smc.equal.level.atr.tolerance", d.equalLevelAtrTolerance()),
                AppConfig.getDouble("strategy.smc.fvg.min.atr.pct", d.minFvgAtrPct()),
                AppConfig.getInt("strategy.smc.volume.avg.period", d.volumeAvgPeriod()),
                AppConfig.getDouble("strategy.smc.volume.spike.ratio", d.volumeSpikeRatio()),
                LocalTime.parse(AppConfig.get("strategy.smc.entry.start", d.entryStart().toString())),
                LocalTime.parse(AppConfig.get("strategy.smc.entry.cutoff", d.entryCutoff().toString())));
    }
}
