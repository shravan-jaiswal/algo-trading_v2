package com.trading.strategy;

public record InstrumentConfig(
        InstrumentType type,
        String         exchange,
        int            expiryOffset,   // 0 = nearest, 1 = next
        int            strikeOffset,   // -1 = ITM, 0 = ATM, +1 = OTM
        int            numberOfLots,   // how many lots to trade (lot size auto-fetched from broker)
        boolean        allowShort
) {
    // ── Common factory methods ──────────────────────────────────────

    public static final InstrumentConfig EQUITY =
            new InstrumentConfig(InstrumentType.EQUITY, "NSE", 0, 0, 1, false);

    public static final InstrumentConfig FUTURES_LONG =
            new InstrumentConfig(InstrumentType.FUTURES, "NFO", 0, 0, 1, false);

    public static final InstrumentConfig FUTURES =
            new InstrumentConfig(InstrumentType.FUTURES, "NFO", 0, 0, 1, true);

    public static InstrumentConfig optionBuy(int expiryOffset, int strikeOffset, int numberOfLots) {
        return new InstrumentConfig(InstrumentType.OPTION_BUY, "NFO",
                expiryOffset, strikeOffset, numberOfLots, false);
    }

    public static InstrumentConfig optionWrite(int expiryOffset, int strikeOffset, int numberOfLots) {
        return new InstrumentConfig(InstrumentType.OPTION_WRITE, "NFO",
                expiryOffset, strikeOffset, numberOfLots, true);
    }

    public boolean isEquity()      { return type == InstrumentType.EQUITY; }
    public boolean isFutures()     { return type == InstrumentType.FUTURES; }
    public boolean isOptionBuy()   { return type == InstrumentType.OPTION_BUY; }
    public boolean isOptionWrite() { return type == InstrumentType.OPTION_WRITE; }
}
