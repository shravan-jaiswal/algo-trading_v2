package com.trading.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record WatchlistItem(
        String      token,
        String      symbol,
        String      exchange,
        String      instrumentType,   // EQ, FUT, CE, PE
        Set<String> strategies        // which strategies may trade this symbol
) {
    public boolean isEquity()  { return "EQ".equalsIgnoreCase(instrumentType); }
    public boolean isFutures() { return "FUT".equalsIgnoreCase(instrumentType); }
    public boolean isOption()  { return "CE".equalsIgnoreCase(instrumentType)
                                      || "PE".equalsIgnoreCase(instrumentType); }

    public boolean hasStrategy(String name) {
        return strategies != null && strategies.contains(name.toUpperCase());
    }

    /** Parse comma-separated strategy names into a Set. */
    public static Set<String> parseStrategies(String csv) {
        if (csv == null || csv.isBlank()) return Set.of("VSRSI");
        return Arrays.stream(csv.split(","))
                .map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String toString() {
        return symbol + "(" + exchange + ":" + instrumentType + " strats=" + strategies + ")";
    }
}
