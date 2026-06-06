package com.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingEngineProtectiveStopTest {

    @Test
    void longPositionUsesHigherProtectiveStop() {
        assertEquals(108, TradingEngine.tighterStop("BUY", 95, 108), 0.001);
    }

    @Test
    void shortPositionUsesLowerProtectiveStop() {
        assertEquals(92, TradingEngine.tighterStop("SELL", 110, 92), 0.001);
    }

    @Test
    void fallsBackWhenOneStopIsUnavailable() {
        assertEquals(95, TradingEngine.tighterStop("BUY", 95, 0), 0.001);
        assertEquals(108, TradingEngine.tighterStop("BUY", 0, 108), 0.001);
    }

    @Test
    void optionBuyTrailDoesNotStartTighterThanFixedStop() {
        assertEquals(0.05, TradingEngine.optionBuyTrailPct(100, 5, 0.01), 0.001);
    }

    @Test
    void optionBuyTrailKeepsWiderConfiguredTrail() {
        assertEquals(0.08, TradingEngine.optionBuyTrailPct(100, 5, 0.08), 0.001);
    }

    @Test
    void optionBuyEntryCanUseDiscountedLimit() {
        assertEquals(95, TradingEngine.optionBuyEntryPrice(100, 0.05), 0.001);
    }
}
