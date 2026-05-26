package com.trading.execution;

import com.trading.risk.RiskConfig;
import com.trading.risk.RiskManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderManagerPriceTickTest {

    @Test
    void usesTenPaiseTickForEquityAndFuturesOrders() throws Exception {
        Method orderTickSize = OrderManager.class.getDeclaredMethod(
                "orderTickSize", String.class, String.class);
        orderTickSize.setAccessible(true);

        assertEquals(0.10, (double) orderTickSize.invoke(null, "CIPLA-EQ", "NSE"), 0.0001);
        assertEquals(0.10, (double) orderTickSize.invoke(null, "CIPLA26MAY26FUT", "NFO"), 0.0001);
        assertEquals(0.05, (double) orderTickSize.invoke(null, "NIFTY26MAY2625000CE", "NFO"), 0.0001);
    }

    @Test
    void oneTickLimitUsesSelectedTickSize() throws Exception {
        OrderManager manager = new OrderManager(
                null,
                new RiskManager(RiskConfig.paperDefaults()),
                true,
                new PaperBroker());
        Method oneTickLimitPrice = OrderManager.class.getDeclaredMethod(
                "oneTickLimitPrice", double.class, double.class, String.class);
        oneTickLimitPrice.setAccessible(true);

        assertEquals(910.10, (double) oneTickLimitPrice.invoke(manager, 910.00, 0.10, "BUY"), 0.0001);
        assertEquals(909.90, (double) oneTickLimitPrice.invoke(manager, 910.00, 0.10, "SELL"), 0.0001);
    }

    @Test
    void deliveryPositionsAreNotRiskManagedOpenPositions() {
        OrderManager manager = new OrderManager(
                null,
                new RiskManager(RiskConfig.paperDefaults()),
                true,
                new PaperBroker());

        manager.restorePosition("manual-delivery", 100, 1, "BUY", "MANUAL-EQ", "DELIVERY");
        manager.restorePosition("algo-future", 200, 1, "BUY", "ALGOFUT", "INTRADAY");

        assertEquals(2, manager.getOpenPositions().size());
        assertEquals(1, manager.getRiskManagedOpenPositionCount());
    }
}
