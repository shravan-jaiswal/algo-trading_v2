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

    @Test
    void trailingStopAdvancesForBoughtOptionPremium() {
        RiskManager risk = riskManager();

        assertEquals(99, risk.initTSL("OPT|CE", 100), 0.001);
        assertEquals(108.9, risk.updateTSL("OPT|CE", 110), 0.001);
        assertFalse(risk.isTSLHit("OPT|CE", 109));
        assertTrue(risk.isTSLHit("OPT|CE", 108));
    }

    @Test
    void trailingStopAdvancesForWrittenOptionPremium() {
        RiskManager risk = riskManager();

        assertEquals(101, risk.initTSLShort("OPT|WRITE_PE", 100), 0.001);
        assertEquals(90.9, risk.updateTSLShort("OPT|WRITE_PE", 90), 0.001);
        assertFalse(risk.isTSLHitShort("OPT|WRITE_PE", 90));
        assertTrue(risk.isTSLHitShort("OPT|WRITE_PE", 91));
    }

    private static RiskManager riskManager() {
        return new RiskManager(new RiskConfig(
                1_000, 0.01, 0.20, 0.10,
                3.0, 0.01, 10, 5, 1.0));
    }
}
