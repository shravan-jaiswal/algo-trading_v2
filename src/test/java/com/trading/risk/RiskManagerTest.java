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
    void positionAllocationLimitAppliesToFixedLotOrders() {
        RiskManager risk = new RiskManager(new RiskConfig(
                500_000, 0.0055, 0.02, 0.046,
                3.0, 0.01, 15, 3, 0.05));

        assertTrue(risk.canOpenTradeForCapital(25_000, "option-premium"));
        assertFalse(risk.canOpenTradeForCapital(25_001, "option-premium"));
    }

    @Test
    void dailyStopsUseNetPnlAndIncludeEstimatedCosts() {
        System.setProperty("risk.estimated.cost.per.closed.trade", "10");
        try {
            RiskManager profitRisk = new RiskManager(new RiskConfig(
                    1_000, 0.01, 0.10, 0.20,
                    3.0, 0.01, 10, 5, 1.0));
            profitRisk.onTradeClosed("WIN1", 130); // net +120
            profitRisk.onTradeClosed("LOSS", -40); // net -50
            profitRisk.onTradeClosed("WIN2", 110); // net +100; cumulative net +170

            assertEquals(170, profitRisk.getNetDailyPnl(), 0.001);
            assertFalse(profitRisk.isHalted());

            RiskManager lossRisk = new RiskManager(new RiskConfig(
                    1_000, 0.01, 0.10, 0.20,
                    3.0, 0.01, 10, 5, 1.0));
            lossRisk.onTradeClosed("WIN", 60);   // net +50
            lossRisk.onTradeClosed("LOSS", -110); // net -120; cumulative net -70

            assertEquals(-70, lossRisk.getNetDailyPnl(), 0.001);
            assertFalse(lossRisk.isHalted());
        } finally {
            System.clearProperty("risk.estimated.cost.per.closed.trade");
        }
    }

    @Test
    void restoresDailyLimitsAfterRestart() {
        System.setProperty("risk.estimated.cost.per.closed.trade", "10");
        try {
            RiskManager risk = new RiskManager(new RiskConfig(
                    1_000, 0.01, 0.20, 0.20,
                    3.0, 0.01, 10, 5, 1.0));

            risk.restoreDailyState(4, 3, 200, 50);

            assertEquals(4, risk.getTradesToday());
            assertEquals(120, risk.getNetDailyPnl(), 0.001);
            assertFalse(risk.isHalted());
        } finally {
            System.clearProperty("risk.estimated.cost.per.closed.trade");
        }
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

    @Test
    void stepTrailingStopAdvancesByEntryStepAfterProfitStep() {
        RiskManager risk = riskManager();

        assertEquals(85.5, risk.initStepTSL("OPT|CE", 95, 0.10, 0.02, 0.01), 0.001);
        assertEquals(85.5, risk.updateTSL("OPT|CE", 96.89), 0.001);
        assertEquals(86.45, risk.updateTSL("OPT|CE", 96.90), 0.001);
        assertEquals(87.40, risk.updateTSL("OPT|CE", 98.80), 0.001);
        assertFalse(risk.isTSLHit("OPT|CE", 87.50));
        assertTrue(risk.isTSLHit("OPT|CE", 87.40));
    }

    private static RiskManager riskManager() {
        return new RiskManager(new RiskConfig(
                1_000, 0.01, 0.20, 0.10,
                3.0, 0.01, 10, 5, 1.0));
    }
}
