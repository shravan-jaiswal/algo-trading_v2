package com.trading.execution;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.OrderParams;
import com.trading.config.AppConfig;
import com.trading.notification.TelegramAlert;
import com.trading.risk.RiskManager;
import com.trading.utils.MarketUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    public record TrackedPosition(double entryPrice, int qty, String side, String symbol) {
        TrackedPosition(double entryPrice, int qty, String side) {
            this(entryPrice, qty, side, "");
        }
    }

    private volatile SmartConnect smartConnect;
    private final RiskManager riskManager;
    private final PaperBroker paperBroker;
    private final boolean paperMode;
    private final double slippageEntry;
    private final double slippageExit;
    private final int limitWaitSeconds;
    private final String productType;

    private final Map<String, TrackedPosition> openPositions = new ConcurrentHashMap<>();
    private final Map<String, String> slOrderIds = new ConcurrentHashMap<>();
    private final Map<String, String> positionProductTypes = new ConcurrentHashMap<>();

    public OrderManager(SmartConnect smartConnect, RiskManager riskManager,
                        boolean paperMode, PaperBroker paperBroker) {
        this.smartConnect = smartConnect;
        this.riskManager = riskManager;
        this.paperMode = paperMode;
        this.paperBroker = paperBroker;
        this.slippageEntry = AppConfig.getDouble("order.slippage.entry.pct", 0.001);
        this.slippageExit = AppConfig.getDouble("order.slippage.exit.pct", 0.002);
        this.limitWaitSeconds = AppConfig.getInt("order.limit.wait.seconds", 120);
        this.productType = AppConfig.get("order.product.type", "INTRADAY");
    }

    public boolean buy(String symbol, String token, String exchange, int qty, double price) {
        if (!riskManager.canTrade()) {
            log.warn("BUY blocked by RiskManager | {}", symbol);
            return false;
        }
        String key = posKey(token, "DEFAULT");
        return buy(symbol, token, exchange, key, qty, price);
    }

    public boolean buy(String symbol, String token, String exchange,
                       String positionKey, int qty, double price) {
        return buy(symbol, token, exchange, positionKey, qty, price, productType);
    }

    public boolean buy(String symbol, String token, String exchange,
                       String positionKey, int qty, double price, String orderProductType) {
        if (!riskManager.canTrade()) return false;
        if (openPositions.containsKey(positionKey)) {
            log.debug("BUY skipped - position already open | {} key={}", symbol, positionKey);
            return false;
        }

        String normalizedProductType = normalizeProductType(orderProductType);

        if (paperMode) {
            boolean ok = paperBroker.openPosition(positionKey, symbol, qty, price, "BUY");
            if (ok) {
                openPositions.put(positionKey, new TrackedPosition(price, qty, "BUY", symbol));
                positionProductTypes.put(positionKey, normalizedProductType);
                riskManager.onTradeOpened(positionKey, price, qty);
                TelegramAlert.send("BUY: " + symbol + " x" + qty + " @ Rs." + fmt(price));
            }
            return ok;
        }

        try {
            double limitPrice = MarketUtils.buyLimitPrice(price, slippageEntry);
            String orderId = placeOrder(symbol, token, exchange, qty, "BUY",
                    limitPrice, normalizedProductType);
            if (orderId == null) return false;

            boolean filled = waitForFill(orderId, limitWaitSeconds);
            if (!filled) {
                cancelOrder(orderId);
                log.warn("BUY order not filled within {}s - cancelled | {}", limitWaitSeconds, symbol);
                return false;
            }

            openPositions.put(positionKey, new TrackedPosition(price, qty, "BUY", symbol));
            positionProductTypes.put(positionKey, normalizedProductType);
            riskManager.onTradeOpened(positionKey, price, qty);
            TelegramAlert.send("BUY FILLED: " + symbol + " x" + qty + " @ Rs." + fmt(price));
            log.info("BUY filled | orderId:{} | {} x{} @ Rs.{} | product={}",
                    orderId, symbol, qty, fmt(price), normalizedProductType);
            return true;
        } catch (Exception e) {
            log.error("BUY failed | {}: {}", symbol, e.getMessage(), e);
            return false;
        }
    }

    public boolean sellShort(String symbol, String token, String exchange,
                             String positionKey, int qty, double price) {
        return sellShort(symbol, token, exchange, positionKey, qty, price, productType);
    }

    public boolean sellShort(String symbol, String token, String exchange,
                             String positionKey, int qty, double price, String orderProductType) {
        if (!riskManager.canTrade()) return false;
        if (openPositions.containsKey(positionKey)) {
            log.debug("SHORT skipped - position already open | {} key={}", symbol, positionKey);
            return false;
        }

        String normalizedProductType = normalizeProductType(orderProductType);

        if (paperMode) {
            boolean ok = paperBroker.openPosition(positionKey, symbol, qty, price, "SELL");
            if (ok) {
                openPositions.put(positionKey, new TrackedPosition(price, qty, "SELL", symbol));
                positionProductTypes.put(positionKey, normalizedProductType);
                riskManager.onTradeOpened(positionKey, price, qty);
                TelegramAlert.send("SHORT: " + symbol + " x" + qty + " @ Rs." + fmt(price));
            }
            return ok;
        }

        try {
            double limitPrice = MarketUtils.sellLimitPrice(price, slippageEntry);
            String orderId = placeOrder(symbol, token, exchange, qty, "SELL",
                    limitPrice, normalizedProductType);
            if (orderId == null) return false;

            boolean filled = waitForFill(orderId, limitWaitSeconds);
            if (!filled) {
                cancelOrder(orderId);
                return false;
            }

            openPositions.put(positionKey, new TrackedPosition(price, qty, "SELL", symbol));
            positionProductTypes.put(positionKey, normalizedProductType);
            riskManager.onTradeOpened(positionKey, price, qty);
            TelegramAlert.send("SHORT FILLED: " + symbol + " x" + qty + " @ Rs." + fmt(price));
            log.info("SHORT filled | orderId:{} | {} x{} @ Rs.{} | product={}",
                    orderId, symbol, qty, fmt(price), normalizedProductType);
            return true;
        } catch (Exception e) {
            log.error("SHORT failed | {}: {}", symbol, e.getMessage(), e);
            return false;
        }
    }

    public boolean closePosition(String symbol, String token, String exchange,
                                 String positionKey, double price) {
        TrackedPosition pos = openPositions.get(positionKey);
        if (pos == null) {
            log.debug("closePosition: no tracked position | key={}", positionKey);
            return false;
        }

        double pnl = calcPnl(pos, price);
        String orderProductType = positionProductTypes.getOrDefault(positionKey, productType);

        if (paperMode) {
            boolean ok = paperBroker.closePosition(positionKey, price);
            if (ok) {
                openPositions.remove(positionKey);
                positionProductTypes.remove(positionKey);
                riskManager.onTradeClosed(positionKey, pnl);
                TelegramAlert.send(String.format("CLOSE: %s @ Rs.%.2f | P&L: %+.2f", symbol, price, pnl));
            }
            return ok;
        }

        try {
            cancelPendingSlOrder(positionKey);

            String closeSide = "BUY".equals(pos.side()) ? "SELL" : "BUY";
            double limitPrice = "SELL".equals(closeSide)
                    ? MarketUtils.sellLimitPrice(price, slippageExit)
                    : MarketUtils.buyLimitPrice(price, slippageExit);

            String orderId = placeOrder(symbol, token, exchange, pos.qty(), closeSide,
                    limitPrice, orderProductType);
            if (orderId == null) return false;

            boolean filled = waitForFill(orderId, limitWaitSeconds);
            if (!filled) {
                log.warn("Close order not filled in {}s for {} - will retry on next tick",
                        limitWaitSeconds, symbol);
                return false;
            }

            openPositions.remove(positionKey);
            positionProductTypes.remove(positionKey);
            riskManager.onTradeClosed(positionKey, pnl);
            TelegramAlert.send(String.format("CLOSED: %s @ Rs.%.2f | P&L: %+.2f", symbol, price, pnl));
            log.info("Position closed | {} @ Rs.{} | P&L: Rs.{} | product={}",
                    symbol, fmt(price), fmt(pnl), orderProductType);
            return true;
        } catch (Exception e) {
            log.error("closePosition failed | {}: {}", symbol, e.getMessage(), e);
            return false;
        }
    }

    public void placeStopLoss(String symbol, String token, String exchange,
                              String positionKey, double triggerPrice) {
        if (paperMode) return;

        TrackedPosition pos = openPositions.get(positionKey);
        if (pos == null) return;

        try {
            String slSide = "BUY".equals(pos.side()) ? "SELL" : "BUY";
            OrderParams params = new OrderParams();
            params.variety = "STOPLOSS";
            params.tradingsymbol = symbol;
            params.symboltoken = token;
            params.transactiontype = slSide;
            params.exchange = exchange;
            params.ordertype = "STOPLOSS_MARKET";
            params.producttype = positionProductTypes.getOrDefault(positionKey, productType);
            params.duration = "DAY";
            params.price = 0.0;
            params.triggerprice = String.valueOf(triggerPrice);
            params.quantity = pos.qty();

            var resp = smartConnect.placeOrder(params, "STOPLOSS");
            if (resp != null && resp.orderId != null) {
                slOrderIds.put(positionKey, resp.orderId);
                log.info("SL placed | {} | trigger:Rs.{} | orderId:{} | product={}",
                        symbol, fmt(triggerPrice), resp.orderId, params.producttype);
            }
        } catch (Exception e) {
            log.error("SL placement failed | {}: {}", symbol, e.getMessage());
        }
    }

    public void setSmartConnect(SmartConnect sc) {
        this.smartConnect = sc;
    }

    public boolean hasOpenPosition(String positionKey) {
        return openPositions.containsKey(positionKey);
    }

    public int getPositionQty(String positionKey) {
        TrackedPosition p = openPositions.get(positionKey);
        return p != null ? p.qty() : 0;
    }

    public double getEntryPrice(String positionKey) {
        TrackedPosition p = openPositions.get(positionKey);
        return p != null ? p.entryPrice() : 0;
    }

    public String getPositionSide(String positionKey) {
        TrackedPosition p = openPositions.get(positionKey);
        return p != null ? p.side() : null;
    }

    public Map<String, TrackedPosition> getOpenPositions() {
        return java.util.Collections.unmodifiableMap(openPositions);
    }

    public void restorePosition(String positionKey, double entryPrice, int qty,
                                String side, String symbol) {
        restorePosition(positionKey, entryPrice, qty, side, symbol, productType);
    }

    public void restorePosition(String positionKey, double entryPrice, int qty,
                                String side, String symbol, String orderProductType) {
        String normalizedProductType = normalizeProductType(orderProductType);
        openPositions.put(positionKey, new TrackedPosition(entryPrice, qty, side, symbol));
        positionProductTypes.put(positionKey, normalizedProductType);
        log.info("Position restored | key={} | {} {} x{} @ Rs.{} | product={}",
                positionKey, side, symbol, qty, fmt(entryPrice), normalizedProductType);
    }

    private String placeOrder(String symbol, String token, String exchange,
                              int qty, String side, double limitPrice,
                              String orderProductType) throws Exception {
        if (smartConnect == null) {
            throw new IllegalStateException("SmartConnect not initialised - call setSmartConnect() after login");
        }

        String normalizedProductType = normalizeProductType(orderProductType);
        OrderParams params = new OrderParams();
        params.variety = "NORMAL";
        params.tradingsymbol = symbol;
        params.symboltoken = token;
        params.transactiontype = side;
        params.exchange = exchange;
        params.ordertype = "LIMIT";
        params.producttype = normalizedProductType;
        params.duration = "DAY";
        params.price = limitPrice;
        params.quantity = qty;

        log.info("Placing order | {} {} x{} @ Rs.{} | exchange={} product={}",
                side, symbol, qty, fmt(limitPrice), exchange, normalizedProductType);

        var resp = smartConnect.placeOrder(params, "NORMAL");
        if (resp == null || resp.orderId == null) {
            log.error("Null response from broker for {} {} x{}", side, symbol, qty);
            return null;
        }
        return resp.orderId;
    }

    private boolean waitForFill(String orderId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000);
                JSONObject history = smartConnect.getOrderHistory(orderId);
                if (history == null) continue;

                var orders = history.optJSONArray("data");
                if (orders == null || orders.isEmpty()) continue;

                JSONObject last = orders.getJSONObject(orders.length() - 1);
                String status = last.optString("orderstatus", "");
                if ("complete".equalsIgnoreCase(status)) return true;
                if ("rejected".equalsIgnoreCase(status)
                        || "cancelled".equalsIgnoreCase(status)) return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Error polling order status for {}: {}", orderId, e.getMessage());
            }
        }
        return false;
    }

    private void cancelOrder(String orderId) {
        try {
            smartConnect.cancelOrder(orderId, "NORMAL");
            log.info("Order cancelled: {}", orderId);
        } catch (Exception e) {
            log.warn("Cancel failed for {}: {}", orderId, e.getMessage());
        }
    }

    private void cancelPendingSlOrder(String positionKey) {
        String slId = slOrderIds.remove(positionKey);
        if (slId != null) cancelOrder(slId);
    }

    private String normalizeProductType(String value) {
        if (value == null || value.isBlank()) return productType;
        return value.trim().toUpperCase();
    }

    private static double calcPnl(TrackedPosition pos, double exitPrice) {
        return "BUY".equals(pos.side())
                ? (exitPrice - pos.entryPrice()) * pos.qty()
                : (pos.entryPrice() - exitPrice) * pos.qty();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static String posKey(String token, String strategy) {
        return token + "|" + strategy;
    }
}
