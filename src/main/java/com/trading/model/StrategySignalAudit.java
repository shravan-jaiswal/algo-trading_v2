package com.trading.model;

import java.time.Instant;
import java.time.LocalDateTime;

public record StrategySignalAudit(
        Instant evaluatedAt,
        String token,
        String symbol,
        String strategyName,
        LocalDateTime candleTs,
        int candleCount,
        String signal,
        String reason,
        double currentPrice,
        double close,
        double rsi,
        double vwap,
        double supertrend,
        Boolean supertrendBullish,
        double adx,
        double volumeRatio,
        int bullScore,
        int bearScore
) {}
