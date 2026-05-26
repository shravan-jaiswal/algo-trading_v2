package com.trading.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskManagerTest {

    @Test
    void capitalGateCanUseBrokerMarginInsteadOfNotional() {
        RiskManager risk = new RiskManager(new RiskConfig(
                1_000, 0.01, 0.20, 0.10,
                3.0, 0.01, 10, 5, 1.0));

        assertTrue(risk.canOpenTradeForCapital(400, "angel-margin"));
        risk.onTradeOpenedWithCapital("A", 400, "angel-margin");

        assertEquals(400, risk.getDeployedCapital(), 0.001);
        assertEquals(600, risk.getAvailableCapital(), 0.001);
        assertFalse(risk.canOpenTradeForCapital(700, "angel-margin"));
    }
}
