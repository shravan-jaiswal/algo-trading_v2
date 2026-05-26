package com.trading;

import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.InstrumentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingEngineVsrsiInstrumentConfigTest {

    private static final String[] KEYS = {
            "strategy.vsrsi.instrument.type",
            "strategy.vsrsi.futures.expiry.offset",
            "strategy.vsrsi.futures.lots",
            "strategy.vsrsi.option.expiry.offset",
            "strategy.vsrsi.option.strike.offset",
            "strategy.vsrsi.option.lots"
    };

    @AfterEach
    void clearProperties() {
        for (String key : KEYS) {
            System.clearProperty(key);
        }
    }

    @Test
    void defaultsVsrsiToFutures() {
        System.clearProperty("strategy.vsrsi.instrument.type");

        InstrumentConfig cfg = TradingEngine.buildVsrsiInstrumentConfig();

        assertEquals(InstrumentType.FUTURES, cfg.type());
        assertEquals("NFO", cfg.exchange());
        assertEquals(0, cfg.expiryOffset());
        assertEquals(1, cfg.numberOfLots());
    }

    @Test
    void canConfigureVsrsiForOptionBuying() {
        System.setProperty("strategy.vsrsi.instrument.type", "OPTION_BUY");
        System.setProperty("strategy.vsrsi.option.expiry.offset", "1");
        System.setProperty("strategy.vsrsi.option.strike.offset", "-1");
        System.setProperty("strategy.vsrsi.option.lots", "2");

        InstrumentConfig cfg = TradingEngine.buildVsrsiInstrumentConfig();

        assertEquals(InstrumentType.OPTION_BUY, cfg.type());
        assertEquals("NFO", cfg.exchange());
        assertEquals(1, cfg.expiryOffset());
        assertEquals(-1, cfg.strikeOffset());
        assertEquals(2, cfg.numberOfLots());
    }
}
