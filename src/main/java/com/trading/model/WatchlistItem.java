package com.trading.model;

public record WatchlistItem(
        String token,
        String symbol,
        String exchange,
        String instrumentType   // EQ, FUT, CE, PE
) {
    public boolean isEquity()  { return "EQ".equalsIgnoreCase(instrumentType); }
    public boolean isFutures() { return "FUT".equalsIgnoreCase(instrumentType); }
    public boolean isOption()  { return "CE".equalsIgnoreCase(instrumentType)
                                      || "PE".equalsIgnoreCase(instrumentType); }

    @Override
    public String toString() { return symbol + "(" + exchange + ":" + instrumentType + ")"; }
}
