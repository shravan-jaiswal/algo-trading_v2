package com.trading.strategy;

import com.trading.config.AppConfig;

public enum HoldingType {
    INTRADAY,
    DELIVERY;

    public boolean isIntraday() {
        return this == INTRADAY;
    }

    public static HoldingType fromConfig(Strategy strategy) {
        String key = "strategy." + familyKey(strategy) + ".holding.type";
        return parse(AppConfig.get(key, "INTRADAY"), key);
    }

    public static HoldingType fromStrategyName(String strategyName) {
        String key = "strategy." + familyKey(strategyName) + ".holding.type";
        return parse(AppConfig.get(key, "INTRADAY"), key);
    }

    public static HoldingType parse(String value, String key) {
        if (value == null || value.isBlank()) return INTRADAY;
        String normalized = value.trim().toUpperCase();
        if ("INTRADY".equals(normalized)) normalized = "INTRADAY";
        if ("DELEVERY".equals(normalized)) normalized = "DELIVERY";
        try {
            return HoldingType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid " + key + "=" + value + " (use INTRADAY or DELIVERY)");
        }
    }

    private static String familyKey(Strategy strategy) {
        return familyKey(strategy != null ? strategy.getName() : null);
    }

    private static String familyKey(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) return "default";
        String name = strategyName.trim().toUpperCase();
        if ("VSRSI".equals(name)) return "vsrsi";
        if ("MICS".equals(name)) return "mics";
        if (name.startsWith("MA_CROSSOVER")) return "ma";
        if (name.startsWith("RSI_")) return "rsi";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", ".");
    }
}
