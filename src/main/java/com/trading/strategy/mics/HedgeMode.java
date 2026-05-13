package com.trading.strategy.mics;

public enum HedgeMode {
    EQUITY,        // no hedge — just trade the underlying or futures
    OPTION_HEDGE,  // buy a protective option (OTM put for long, OTM call for short)
    SPREAD         // full spread: write ATM + buy OTM hedge
}
