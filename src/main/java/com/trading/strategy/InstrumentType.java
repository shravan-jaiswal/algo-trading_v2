package com.trading.strategy;

public enum InstrumentType {
    EQUITY,
    FUTURES,
    OPTION_BUY,    // buy a call/put (pay premium)
    OPTION_WRITE   // sell/write a call/put (collect premium)
}
