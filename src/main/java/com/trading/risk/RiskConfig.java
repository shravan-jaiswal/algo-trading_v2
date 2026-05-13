package com.trading.risk;

import com.trading.config.AppConfig;

public record RiskConfig(
        double capital,
        double maxRiskPerTrade,
        double maxDailyLoss,
        double maxDailyProfit,
        double riskRewardRatio,
        double tslTrailPct,
        int    maxTradesPerDay,
        int    maxOpenPositions,
        double maxPositionSize
) {
    public RiskConfig {
        if (capital <= 0)           throw new IllegalArgumentException("capital must be > 0");
        if (maxRiskPerTrade <= 0)   throw new IllegalArgumentException("maxRiskPerTrade must be > 0");
        if (maxDailyLoss <= 0)      throw new IllegalArgumentException("maxDailyLoss must be > 0");
        if (maxTradesPerDay <= 0)   throw new IllegalArgumentException("maxTradesPerDay must be > 0");
        if (maxOpenPositions <= 0)  throw new IllegalArgumentException("maxOpenPositions must be > 0");
        if (maxPositionSize <= 0)   throw new IllegalArgumentException("maxPositionSize must be > 0");
    }

    public static RiskConfig fromAppConfig() {
        return new RiskConfig(
            AppConfig.getDouble("risk.capital",            4_000_000),
            AppConfig.getDouble("risk.per.trade",          0.01),
            AppConfig.getDouble("risk.max.daily.loss",     0.02),
            AppConfig.getDouble("risk.max.daily.profit",   0.10),
            AppConfig.getDouble("risk.reward.ratio",       3.0),
            AppConfig.getDouble("risk.tsl.trail.pct",      0.01),
            AppConfig.getInt(   "risk.max.trades.day",     100),
            AppConfig.getInt(   "risk.max.open.positions", 30),
            AppConfig.getDouble("risk.max.position.size",  0.10)
        );
    }

    public static RiskConfig paperDefaults() {
        return new RiskConfig(500_000, 0.01, 0.02, 0.10, 3.0, 0.01, 50, 10, 0.20);
    }
}
