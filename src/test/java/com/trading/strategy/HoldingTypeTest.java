package com.trading.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HoldingTypeTest {

    @Test
    void acceptsDeliveryTypoAlias() {
        assertEquals(HoldingType.DELIVERY,
                HoldingType.parse("DELEVERY", "strategy.test.holding.type"));
    }

    @Test
    void acceptsIntradayTypoAlias() {
        assertEquals(HoldingType.INTRADAY,
                HoldingType.parse("INTRADY", "strategy.test.holding.type"));
    }

    @Test
    void rejectsUnknownHoldingType() {
        assertThrows(IllegalArgumentException.class,
                () -> HoldingType.parse("SWING", "strategy.test.holding.type"));
    }
}
