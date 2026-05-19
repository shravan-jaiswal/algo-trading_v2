package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.strategy.Strategy.Signal;

public enum TradeType {
    LONG,
    SHORT,
    BOTH;

    public boolean allows(Signal signal) {
        if (signal == null || signal == Signal.NONE) return true;
        return switch (this) {
            case BOTH  -> true;
            case LONG  -> !signal.isShortEntry() && signal != Signal.SHORT_EXIT;
            case SHORT -> !signal.isLongEntry() && signal != Signal.LONG_EXIT;
        };
    }

    public static TradeType fromConfig(Strategy strategy) {
        String key = "strategy." + familyKey(strategy) + ".trade.type";
        return parse(AppConfig.get(key, "BOTH"), key);
    }

    public static TradeType parse(String value, String key) {
        if (value == null || value.isBlank()) return BOTH;
        try {
            return TradeType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid " + key + "=" + value + " (use LONG, SHORT, or BOTH)");
        }
    }

    private static String familyKey(Strategy strategy) {
        if (strategy == null || strategy.getName() == null) return "default";
        String name = strategy.getName().trim().toUpperCase();
        if ("VSRSI".equals(name)) return "vsrsi";
        if ("MICS".equals(name)) return "mics";
        if (name.startsWith("MA_CROSSOVER")) return "ma";
        if (name.startsWith("RSI_")) return "rsi";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", ".");
    }
}
