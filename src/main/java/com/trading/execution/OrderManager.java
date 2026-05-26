package com.trading.execution;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.MarginParams;
import com.angelbroking.smartapi.models.OrderParams;
import com.trading.config.AppConfig;
import com.trading.notification.TelegramAlert;
import com.trading.risk.RiskManager;
import com.trading.utils.MarketUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public class OrderManager {

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    public record TrackedPosition(double entryPrice, int qty, String side, String symbol) {
        TrackedPosition(double entryPrice, int qty, String side) {
            this(entryPrice, qty, side, "");
        }
    }

    private record BrokerPosition(int netQty, String symbol, String token, String exchange, String productType) {
        int absQty() { return Math.abs(netQty); }
        boolean isFlat() { return netQty == 0; }
        boolean matchesOpeningSide(String openingSide) {
            return "SELL".equalsIgnoreCase(openingSide) ? netQty < 0 : netQty > 0;
        }
    }

    private record ClosePlan(boolean shouldPlaceOrder, int qty, boolean success) {}
    private record OpeningCapital(double amount, String source) {}
    private record SlOrder(String orderId, String symbol, String token, String exchange, String variety) {}
    private record FillResult(boolean filled, boolean shouldCancel) {}
    private record OrderSnapshot(String status, int filledQty, String message) {}

    private volatile SmartConnect smartConnect;
    private final RiskManager riskManager;
    private final PaperBroker paperBroker;
    private final boolean paperMode;
    private final boolean protectedLimitOrders;
    private final int limitWaitSeconds;
    private final int brokerFlatEntryCooldownSeconds;
    private final boolean marginCalculatorEnabled;
    private final boolean brokerFundsCheckEnabled;
    private final double marginBuffer;
    private final String productType;
    private static final long BROKER_POSITION_CACHE_MS = 5000L;

    private final Map<String, TrackedPosition> openPositions = new ConcurrentHashMap<>();
    private final Map<String, String> slOrderIds = new ConcurrentHashMap<>();
    private final Map<String, String> positionProductTypes = new ConcurrentHashMap<>();
    private final Map<String, Long> brokerFlatEntryBlockedUntil = new ConcurrentHashMap<>();
    private volatile JSONObject cachedBrokerPositions;
    private volatile long cachedBrokerPositionsUntil;

    public OrderManager(SmartConnect smartConnect, RiskManager riskManager,
                        boolean paperMode, PaperBroker paperBroker) {
        this.smartConnect = smartConnect;
        this.riskManager = riskManager;
        this.paperMode = paperMode;
        this.paperBroker = paperBroker;
        this.protectedLimitOrders = AppConfig.getBool("order.protected.limit", true);
        this.limitWaitSeconds = AppConfig.getInt("order.limit.wait.seconds", 120);
        this.brokerFlatEntryCooldownSeconds = AppConfig.getInt("order.broker.flat.entry.cooldown.seconds", 300);
        this.marginCalculatorEnabled = AppConfig.getBool("order.margin.calculator.enabled", true);
        this.brokerFundsCheckEnabled = AppConfig.getBool("order.broker.funds.check.enabled", true);
        this.marginBuffer = Math.max(0, AppConfig.getDouble("order.margin.buffer.rs", 1000));
        this.productType = AppConfig.get("order.product.type", "INTRADAY");
    }

    public boolean buy(String symbol, String token, String exchange, int qty, double price) {
        String key = posKey(token, "DEFAULT");
        return buy(symbol, token, exchange, key, qty, price);
    }

    public boolean buy(String symbol, String token, String exchange,
                       String positionKey, int qty, double price) {
        return buy(symbol, token, exchange, positionKey, qty, price, productType);
    }

    public boolean buy(String symbol, String token, String exchange,
                       String positionKey, int qty, double price, String orderProductType) {
        if (openPositions.containsKey(positionKey)) {
            log.debug("BUY skipped - position already open | {} key={}", symbol, positionKey);
            return false;
        }
        if (isBrokerFlatEntryCooldownActive("BUY", symbol)) return false;
        if (hasTrackedPositionForSymbol(symbol, positionKey)) {
            log.warn("BUY blocked - another local position already exists for symbol | {} key={}",
                    symbol, positionKey);
            return false;
        }

        String normalizedProductType = normalizeProductType(orderProductType);

        if (paperMode) {
            if (!riskManager.canOpenTrade(price, qty)) return false;
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
            if (hasBrokerPositionForSymbol(symbol, token, exchange)) {
                log.error("BUY blocked - broker already has open position for symbol | {} token={}",
                        symbol, token);
                TelegramAlert.sendAsync("BUY blocked: broker already has open position for " + symbol);
                return false;
            }

            OpeningCapital openingCapital = openingCapital(
                    symbol, token, exchange, qty, price, "BUY", normalizedProductType);
            syncRiskManagedOpenPositionCount();
            if (!riskManager.canOpenTradeForCapital(openingCapital.amount(), openingCapital.source())) {
                return false;
            }
            if (!hasBrokerFundsFor(symbol, openingCapital.amount())) return false;

            String orderId = placeOrderWithSlippageProtection(symbol, token, exchange, qty, "BUY",
                    price, false, normalizedProductType);
            if (orderId == null) return false;

            FillResult fillResult = waitForFill(orderId, limitWaitSeconds);
            if (!fillResult.filled()) {
                if (!resolveEntryFillAfterWait(symbol, token, exchange, qty, "BUY",
                        orderId, normalizedProductType, fillResult)) return false;
            }

            openPositions.put(positionKey, new TrackedPosition(price, qty, "BUY", symbol));
            positionProductTypes.put(positionKey, normalizedProductType);
            riskManager.onTradeOpenedWithCapital(positionKey, openingCapital.amount(), openingCapital.source());
            invalidateBrokerPositionCache();
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
        if (openPositions.containsKey(positionKey)) {
            log.debug("SHORT skipped - position already open | {} key={}", symbol, positionKey);
            return false;
        }
        if (isBrokerFlatEntryCooldownActive("SHORT", symbol)) return false;
        if (hasTrackedPositionForSymbol(symbol, positionKey)) {
            log.warn("SHORT blocked - another local position already exists for symbol | {} key={}",
                    symbol, positionKey);
            return false;
        }

        String normalizedProductType = normalizeProductType(orderProductType);

        if (paperMode) {
            if (!riskManager.canOpenTrade(price, qty)) return false;
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
            if (hasBrokerPositionForSymbol(symbol, token, exchange)) {
                log.error("SHORT blocked - broker already has open position for symbol | {} token={}",
                        symbol, token);
                TelegramAlert.sendAsync("SHORT blocked: broker already has open position for " + symbol);
                return false;
            }

            OpeningCapital openingCapital = openingCapital(
                    symbol, token, exchange, qty, price, "SELL", normalizedProductType);
            syncRiskManagedOpenPositionCount();
            if (!riskManager.canOpenTradeForCapital(openingCapital.amount(), openingCapital.source())) {
                return false;
            }
            if (!hasBrokerFundsFor(symbol, openingCapital.amount())) return false;

            String orderId = placeOrderWithSlippageProtection(symbol, token, exchange, qty, "SELL",
                    price, false, normalizedProductType);
            if (orderId == null) return false;

            FillResult fillResult = waitForFill(orderId, limitWaitSeconds);
            if (!fillResult.filled()) {
                if (!resolveEntryFillAfterWait(symbol, token, exchange, qty, "SELL",
                        orderId, normalizedProductType, fillResult)) return false;
            }

            openPositions.put(positionKey, new TrackedPosition(price, qty, "SELL", symbol));
            positionProductTypes.put(positionKey, normalizedProductType);
            riskManager.onTradeOpenedWithCapital(positionKey, openingCapital.amount(), openingCapital.source());
            invalidateBrokerPositionCache();
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
            ClosePlan closePlan = brokerVerifiedClosePlan(symbol, token, exchange, positionKey, pos);
            if (!closePlan.shouldPlaceOrder()) {
                return closePlan.success();
            }

            if (!cancelPendingSlOrder(positionKey, symbol, token, exchange)) {
                return false;
            }

            String closeSide = "BUY".equals(pos.side()) ? "SELL" : "BUY";
            String orderId = placeOrderWithSlippageProtection(symbol, token, exchange,
                    closePlan.qty(), closeSide, price, true, orderProductType);
            if (orderId == null) return false;

            FillResult fillResult = waitForFill(orderId, limitWaitSeconds);
            if (!fillResult.filled()) {
                if (fillResult.shouldCancel()) {
                    cancelOrder(orderId);
                    log.warn("Close order not filled in {}s for {} - cancelled; will retry on next tick",
                            limitWaitSeconds, symbol);
                }
                return false;
            }

            openPositions.remove(positionKey);
            positionProductTypes.remove(positionKey);
            riskManager.onTradeClosed(positionKey, pnl);
            invalidateBrokerPositionCache();
            TelegramAlert.send(String.format("CLOSED: %s @ Rs.%.2f | P&L: %+.2f", symbol, price, pnl));
            log.info("Position closed | {} @ Rs.{} | P&L: Rs.{} | product={}",
                    symbol, fmt(price), fmt(pnl), orderProductType);
            return true;
        } catch (Exception e) {
            log.error("closePosition failed | {}: {}", symbol, e.getMessage(), e);
            return false;
        }
    }

    public boolean placeStopLoss(String symbol, String token, String exchange,
                                 String positionKey, double triggerPrice) {
        if (paperMode) return true;
        if (triggerPrice <= 0) return false;

        TrackedPosition pos = openPositions.get(positionKey);
        if (pos == null) {
            log.warn("SL placement skipped - no tracked position | {} key={}", symbol, positionKey);
            return false;
        }

        try {
            List<SlOrder> existing = findOpenStopLossOrders(symbol, token, exchange);
            if (existing != null && !existing.isEmpty()) {
                SlOrder sl = existing.get(0);
                slOrderIds.put(positionKey, sl.orderId());
                log.info("SL already active | {} | trigger:Rs.{} | orderId:{}",
                        symbol, fmt(triggerPrice), sl.orderId());
                return true;
            }
            if (existing == null) {
                log.warn("Could not verify existing SL orders before placement | {} [{}]", symbol, token);
            }

            String slSide = "BUY".equals(pos.side()) ? "SELL" : "BUY";
            double triggerTick = orderTickSize(symbol, exchange);
            double roundedTrigger = "SELL".equalsIgnoreCase(slSide)
                    ? roundDownToTick(triggerPrice, triggerTick)
                    : roundUpToTick(triggerPrice, triggerTick);
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
            params.triggerprice = fmt(roundedTrigger);
            params.quantity = pos.qty();

            var resp = smartConnect.placeOrder(params, "STOPLOSS");
            if (resp != null && resp.orderId != null) {
                slOrderIds.put(positionKey, resp.orderId);
                log.info("SL placed | {} | trigger:Rs.{} | tick:Rs.{} | orderId:{} | product={}",
                        symbol, fmt(roundedTrigger), fmt(triggerTick), resp.orderId, params.producttype);
                return true;
            }
            log.error("SL placement returned null order id | {} key={}", symbol, positionKey);
            return false;
        } catch (Exception e) {
            log.error("SL placement failed | {}: {}", symbol, e.getMessage());
            return false;
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

    public Map<String, TrackedPosition> getRiskManagedOpenPositions() {
        return openPositions.entrySet().stream()
                .filter(e -> isRiskManagedPosition(e.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int getRiskManagedOpenPositionCount() {
        return getRiskManagedOpenPositions().size();
    }

    public boolean isRiskManagedPosition(String positionKey) {
        return isRiskManagedProductType(positionProductTypes.getOrDefault(positionKey, productType));
    }

    public boolean hasOpenPositionForSymbol(String symbol) {
        return hasTrackedPositionForSymbol(symbol, "");
    }

    public boolean brokerHasMatchingOpenPosition(String symbol, String token,
                                                 String exchange, String openingSide) {
        if (paperMode) return true;
        try {
            BrokerPosition brokerPosition = fetchBrokerPosition(symbol, token, exchange);
            return brokerPosition != null
                    && !brokerPosition.isFlat()
                    && brokerPosition.matchesOpeningSide(openingSide);
        } catch (Exception e) {
            log.warn("Broker position check unavailable for restore | {} [{}]: {}",
                    symbol, token, e.getMessage());
            return true;
        }
    }

    public void recordBrokerFlatSync(String symbol) {
        if (paperMode || brokerFlatEntryCooldownSeconds <= 0) return;
        String key = underlyingKey(symbol);
        if (key.isBlank()) return;
        long until = System.currentTimeMillis() + brokerFlatEntryCooldownSeconds * 1000L;
        brokerFlatEntryBlockedUntil.put(key, until);
        log.warn("Broker-flat sync cooldown set | {} key={} seconds={}",
                symbol, key, brokerFlatEntryCooldownSeconds);
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

    public double estimateDeployedCapital(String symbol, String token, String exchange,
                                          int qty, double price, String side,
                                          String orderProductType) {
        return openingCapital(symbol, token, exchange, qty, price, side,
                normalizeProductType(orderProductType)).amount();
    }

    private ClosePlan brokerVerifiedClosePlan(String symbol, String token, String exchange,
                                              String positionKey, TrackedPosition local) {
        try {
            BrokerPosition brokerPosition = fetchBrokerPosition(symbol, token, exchange);
            if (brokerPosition == null || brokerPosition.isFlat()) {
                if (!cancelPendingSlOrder(positionKey, symbol, token, exchange)) {
                    log.warn("Broker-flat sync could not verify/cancel protective SL | {} key={}",
                            symbol, positionKey);
                }
                openPositions.remove(positionKey);
                positionProductTypes.remove(positionKey);
                riskManager.onTradeClosed(positionKey, 0);
                recordBrokerFlatSync(symbol);
                log.warn("CLOSE skipped - broker position already flat | {} key={} | local state synced",
                        symbol, positionKey);
                TelegramAlert.sendAsync("Broker position already flat - skipped exit order: " + symbol);
                return new ClosePlan(false, 0, true);
            }

            if (!brokerPosition.matchesOpeningSide(local.side())) {
                log.error("CLOSE blocked - broker side mismatch | {} key={} | localSide={} brokerNetQty={}",
                        symbol, positionKey, local.side(), brokerPosition.netQty());
                TelegramAlert.sendAsync("Exit blocked: broker side mismatch for " + symbol
                        + " netQty=" + brokerPosition.netQty());
                return new ClosePlan(false, 0, false);
            }

            int brokerQty = brokerPosition.absQty();
            if (brokerQty != local.qty()) {
                log.warn("CLOSE qty adjusted to broker qty | {} key={} | localQty:{} brokerQty:{}",
                        symbol, positionKey, local.qty(), brokerQty);
            }
            return new ClosePlan(true, brokerQty, true);
        } catch (Exception e) {
            log.error("Broker position check failed before exit | {} key={}: {} | exit blocked",
                    symbol, positionKey, e.getMessage());
            TelegramAlert.sendAsync("Exit blocked: broker position check failed for " + symbol);
            return new ClosePlan(false, 0, false);
        }
    }

    private OpeningCapital openingCapital(String symbol, String token, String exchange,
                                          int qty, double price, String side,
                                          String orderProductType) {
        double notional = price * qty;
        if (paperMode || !marginCalculatorEnabled || !"NFO".equalsIgnoreCase(exchange)) {
            return new OpeningCapital(notional, "notional");
        }

        double margin = requiredMargin(symbol, token, exchange, qty, price, side, orderProductType);
        if (margin > 0) {
            double required = margin + marginBuffer;
            log.info("Angel margin | {} {} x{} @ Rs.{} | margin:Rs.{} buffer:Rs.{} deployed:Rs.{} notional:Rs.{}",
                    side, symbol, qty, fmt(price), fmt(margin), fmt(marginBuffer),
                    fmt(required), fmt(notional));
            return new OpeningCapital(required, "angel-margin");
        }

        log.warn("Angel margin unavailable - falling back to notional capital | {} {} x{} @ Rs.{} notional:Rs.{}",
                side, symbol, qty, fmt(price), fmt(notional));
        return new OpeningCapital(notional, "notional-fallback");
    }

    private double requiredMargin(String symbol, String token, String exchange,
                                  int qty, double price, String side,
                                  String orderProductType) {
        if (smartConnect == null) return -1;

        double margin = requiredMarginWithOrderType(symbol, token, exchange, qty, price, side, orderProductType);
        if (margin > 0) return margin;

        MarginParams params = new MarginParams();
        params.exchange = exchange;
        params.token = token;
        params.quantity = qty;
        params.price = price;
        params.productType = orderProductType;
        params.tradeType = side;

        try {
            JSONObject resp = smartConnect.getMarginDetails(List.of(params));
            if (resp == null || (resp.has("status") && !resp.optBoolean("status", false))) {
                log.warn("getMarginDetails failed | {} [{}] resp:{}", symbol, token, resp);
                return -1;
            }
            JSONObject data = resp.optJSONObject("data");
            if (data == null && resp.has("totalMarginRequired")) data = resp;
            if (data == null) {
                log.warn("getMarginDetails missing data | {} [{}] resp:{}", symbol, token, resp);
                return -1;
            }
            margin = firstPositiveDouble(data,
                    "totalMarginRequired", "totalMargin", "marginRequired", "requiredMargin");
            if (margin <= 0) {
                log.warn("getMarginDetails missing margin | {} [{}] data:{}", symbol, token, data);
                return -1;
            }
            return margin;
        } catch (SmartAPIException e) {
            log.warn("getMarginDetails SmartAPIException | {} [{}]: {}", symbol, token, e.message);
            return -1;
        } catch (Exception e) {
            log.warn("getMarginDetails failed | {} [{}]: {}", symbol, token, e.getMessage());
            return -1;
        }
    }

    private double requiredMarginWithOrderType(String symbol, String token, String exchange,
                                               int qty, double price, String side,
                                               String orderProductType) {
        try {
            JSONArray positions = new JSONArray();
            JSONObject leg = new JSONObject();
            leg.put("exchange", exchange);
            leg.put("qty", qty);
            leg.put("price", price);
            leg.put("productType", orderProductType);
            leg.put("token", token);
            leg.put("tradeType", side);
            leg.put("orderType", "LIMIT");
            positions.put(leg);

            JSONObject body = new JSONObject();
            body.put("positions", positions);

            JSONObject resp = postSmartApiRoute("api.margin.batch", body);
            if (resp == null || (resp.has("status") && !resp.optBoolean("status", false))) {
                log.warn("margin batch failed | {} [{}] resp:{}", symbol, token, resp);
                return -1;
            }

            JSONObject data = resp.optJSONObject("data");
            if (data == null && resp.has("totalMarginRequired")) data = resp;
            if (data == null) {
                log.warn("margin batch missing data | {} [{}] resp:{}", symbol, token, resp);
                return -1;
            }

            double margin = firstPositiveDouble(data,
                    "totalMarginRequired", "totalMargin", "marginRequired", "requiredMargin");
            if (margin <= 0) {
                log.warn("margin batch missing margin | {} [{}] data:{}", symbol, token, data);
                return -1;
            }
            return margin;
        } catch (Exception e) {
            log.warn("margin batch failed | {} [{}]: {}", symbol, token, e.getMessage());
            return -1;
        }
    }

    private JSONObject postSmartApiRoute(String routeKey, JSONObject body) throws Exception {
        java.lang.reflect.Field routesField = SmartConnect.class.getDeclaredField("routes");
        routesField.setAccessible(true);
        Object routes = routesField.get(smartConnect);
        String route = (String) routes.getClass()
                .getMethod("get", String.class)
                .invoke(routes, routeKey);

        java.lang.reflect.Field handlerField = SmartConnect.class.getDeclaredField("smartAPIRequestHandler");
        handlerField.setAccessible(true);
        Object handler = handlerField.get(smartConnect);
        return (JSONObject) handler.getClass()
                .getMethod("postRequest", String.class, String.class, JSONObject.class, String.class)
                .invoke(handler, smartConnect.getApiKey(), route, body, smartConnect.getAccessToken());
    }

    private boolean hasBrokerFundsFor(String symbol, double requiredCapital) {
        if (!brokerFundsCheckEnabled) return true;
        double funds = availableBrokerFunds();
        if (funds < 0) {
            log.warn("Broker funds check unavailable - continuing with local risk capital | {}", symbol);
            return true;
        }
        if (funds < requiredCapital) {
            log.warn("Trade blocked by broker funds | {} required:Rs.{} availableBroker:Rs.{}",
                    symbol, fmt(requiredCapital), fmt(funds));
            return false;
        }
        log.debug("Broker funds OK | {} required:Rs.{} availableBroker:Rs.{}",
                symbol, fmt(requiredCapital), fmt(funds));
        return true;
    }

    private double entryLimitPrice(String side, double referencePrice, String symbol, String exchange) {
        double tick = orderTickSize(symbol, exchange);
        double limitPrice = oneTickLimitPrice(referencePrice, tick, side);
        log.info("ENTRY LIMIT resolved | side:{} | {} | ltp:Rs.{} | tick:Rs.{} | limit:Rs.{} | wait:{}s",
                side, symbol, fmt(referencePrice), fmt(tick), fmt(limitPrice), limitWaitSeconds);
        return limitPrice;
    }

    private double exitLimitPrice(String side, double referencePrice, String symbol, String exchange) {
        double tick = orderTickSize(symbol, exchange);
        double limitPrice = oneTickLimitPrice(referencePrice, tick, side);
        log.info("EXIT LIMIT resolved | side:{} | {} | ltp:Rs.{} | tick:Rs.{} | limit:Rs.{} | wait:{}s",
                side, symbol, fmt(referencePrice), fmt(tick), fmt(limitPrice), limitWaitSeconds);
        return limitPrice;
    }

    private double oneTickLimitPrice(double referencePrice, double tickSize, String side) {
        double tick = normalizedTickSize(tickSize);
        double raw = "SELL".equalsIgnoreCase(side)
                ? referencePrice - tick
                : referencePrice + tick;
        return "SELL".equalsIgnoreCase(side)
                ? roundDownToTick(raw, tick)
                : roundUpToTick(raw, tick);
    }

    private static double normalizedTickSize(double tickSize) {
        return tickSize > 0 ? tickSize : MarketUtils.TICK_EQUITY;
    }

    private static double orderTickSize(String symbol, String exchange) {
        if ("NSE".equalsIgnoreCase(exchange)) return MarketUtils.TICK_EQUITY;
        if ("NFO".equalsIgnoreCase(exchange) && isFutureSymbol(symbol)) return MarketUtils.TICK_FUTURES;
        return MarketUtils.TICK_OPTIONS;
    }

    private static boolean isFutureSymbol(String symbol) {
        return symbol != null && symbol.trim().toUpperCase().endsWith("FUT");
    }

    private static double roundUpToTick(double price, double tickSize) {
        double tick = normalizedTickSize(tickSize);
        return roundCurrency(Math.ceil((price / tick) - 1e-9) * tick);
    }

    private static double roundDownToTick(double price, double tickSize) {
        double tick = normalizedTickSize(tickSize);
        return roundCurrency(Math.floor((price / tick) + 1e-9) * tick);
    }

    private static double roundCurrency(double price) {
        return Math.round(price * 100.0) / 100.0;
    }

    private double availableBrokerFunds() {
        if (smartConnect == null) return -1;
        try {
            JSONObject resp = smartConnect.getRMS();
            if (resp == null) return -1;
            JSONObject data = resp.has("net") || resp.has("availablecash")
                    ? resp
                    : resp.optJSONObject("data");
            if (data == null) return -1;
            double funds = firstPositiveDouble(data,
                    "net", "availablecash", "availableCash", "availablefunds", "availableFunds");
            return funds > 0 ? funds : -1;
        } catch (Exception e) {
            log.warn("getRMS failed: {}", e.getMessage());
            return -1;
        }
    }

    private String placeOrderWithSlippageProtection(String symbol, String token, String exchange,
                                                    int qty, String side, double referencePrice,
                                                    boolean exitOrder,
                                                    String orderProductType) throws Exception {
        if (!protectedLimitOrders || referencePrice <= 0) {
            return placeMarketOrder(symbol, token, exchange, qty, side, orderProductType);
        }

        double limitPrice = exitOrder
                ? exitLimitPrice(side, referencePrice, symbol, exchange)
                : entryLimitPrice(side, referencePrice, symbol, exchange);
        return placeLimitOrder(symbol, token, exchange, qty, side, limitPrice, orderProductType);
    }

    private String placeLimitOrder(String symbol, String token, String exchange,
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

        log.info("LIMIT placed | {} {} x{} @ Rs.{} | exchange={} product={}",
                side, symbol, qty, fmt(limitPrice), exchange, normalizedProductType);

        var resp = smartConnect.placeOrder(params, "NORMAL");
        if (resp == null || resp.orderId == null) {
            log.error("Null response from broker for {} {} x{}", side, symbol, qty);
            return null;
        }
        return resp.orderId;
    }

    private String placeMarketOrder(String symbol, String token, String exchange,
                                    int qty, String side,
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
        params.ordertype = "MARKET";
        params.producttype = normalizedProductType;
        params.duration = "DAY";
        params.quantity = qty;

        log.warn("MARKET order placed | {} {} x{} | exchange={} product={} | protectedLimitOrders={}",
                side, symbol, qty, exchange, normalizedProductType, protectedLimitOrders);

        var resp = smartConnect.placeOrder(params, "NORMAL");
        if (resp == null || resp.orderId == null) {
            log.error("Null response from broker for {} {} x{}", side, symbol, qty);
            return null;
        }
        return resp.orderId;
    }

    private FillResult waitForFill(String orderId, int timeoutSeconds) {
        if (isBlank(orderId)) return new FillResult(false, false);

        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
                OrderSnapshot snapshot = fetchOrderSnapshot(orderId);
                if (isCompleteStatus(snapshot.status())) return new FillResult(true, false);
                if (isTerminalUnfilledStatus(snapshot.status())) {
                    log.warn("Order terminal before fill | orderId:{} status:{} filledQty:{} message:{}",
                            orderId, snapshot.status(), snapshot.filledQty(), snapshot.message());
                    return new FillResult(false, false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FillResult(false, false);
            } catch (Exception e) {
                log.warn("Error polling order status for {}: {}", orderId, e.getMessage());
            }
        }

        OrderSnapshot finalSnapshot = fetchOrderSnapshot(orderId);
        if (isCompleteStatus(finalSnapshot.status())) return new FillResult(true, false);
        log.warn("Order wait timed out | orderId:{} status:{} filledQty:{} message:{}",
                orderId, finalSnapshot.status(), finalSnapshot.filledQty(), finalSnapshot.message());
        return new FillResult(false, true);
    }

    private boolean resolveEntryFillAfterWait(String symbol, String token, String exchange,
                                              int qty, String openingSide, String orderId,
                                              String orderProductType, FillResult fillResult) {
        OrderSnapshot beforeCancel = fetchOrderSnapshot(orderId);
        int brokerQtyBeforeCancel = brokerOpenQtyForSide(symbol, token, exchange, openingSide);
        int filledBeforeCancel = Math.max(beforeCancel.filledQty(), brokerQtyBeforeCancel);
        if (filledBeforeCancel >= qty) {
            log.info("LIMIT treated as filled by broker verification | orderId:{} | side:{} | {} x{} | status:{} brokerQty:{}",
                    orderId, openingSide, symbol, qty, beforeCancel.status(), brokerQtyBeforeCancel);
            return true;
        }

        if (fillResult.shouldCancel()) {
            cancelOrder(orderId);
            log.warn("{} order not filled within {}s - cancelled | {}",
                    openingSide, limitWaitSeconds, symbol);
        }

        OrderSnapshot afterCancel = fetchOrderSnapshot(orderId);
        int brokerQtyAfterCancel = brokerOpenQtyForSide(symbol, token, exchange, openingSide);
        int filledQty = Math.max(
                Math.max(beforeCancel.filledQty(), afterCancel.filledQty()),
                Math.max(brokerQtyBeforeCancel, brokerQtyAfterCancel));
        if (filledQty >= qty) {
            log.info("LIMIT filled but order status missed | orderId:{} | side:{} | {} x{} | statusBefore:{} statusAfter:{} brokerQty:{}",
                    orderId, openingSide, symbol, qty,
                    beforeCancel.status(), afterCancel.status(), brokerQtyAfterCancel);
            return true;
        }

        if (filledQty > 0) {
            flattenPartialEntry(symbol, token, exchange, openingSide,
                    filledQty, orderId, orderProductType);
        }
        return false;
    }

    private OrderSnapshot fetchOrderSnapshot(String orderId) {
        try {
            JSONObject resp = smartConnect.getIndividualOrderDetails(orderId);
            JSONObject data = resp == null ? null : resp.optJSONObject("data");
            if (data == null) {
                return new OrderSnapshot("", 0, resp == null ? "" : resp.optString("message", ""));
            }
            String status = firstString(data, "status", "orderstatus", "orderStatus").toLowerCase();
            Integer filledQty = firstInt(data,
                    "filledshares", "filledShares",
                    "filledqty", "filledQty",
                    "filled_quantity", "filledQuantity",
                    "filled");
            String message = firstString(data,
                    "text", "message", "rejreason", "rejectreason", "rejectionreason",
                    "statusMessage", "orderstatus");
            return new OrderSnapshot(status, filledQty == null ? 0 : Math.max(0, filledQty), message);
        } catch (Throwable e) {
            log.warn("Order status check failed | orderId:{} | {}", orderId, e.getMessage());
            return new OrderSnapshot("", 0, "");
        }
    }

    private void cancelOrder(String orderId) {
        try {
            smartConnect.cancelOrder(orderId, "NORMAL");
            log.info("Order cancelled: {}", orderId);
        } catch (Exception e) {
            log.warn("Cancel failed for {}: {}", orderId, e.getMessage());
        }
    }

    private boolean cancelPendingSlOrder(String positionKey, String symbol, String token, String exchange) {
        String slId = slOrderIds.remove(positionKey);
        boolean trackedSlCancelled = false;
        if (slId != null) {
            try {
                smartConnect.cancelOrder(slId, "STOPLOSS");
                trackedSlCancelled = true;
                log.info("Tracked SL cancelled | {} | orderId:{} | key={}",
                        symbol, slId, positionKey);
            } catch (Exception e) {
                log.error("Tracked SL cancel failed | {} orderId:{} key={}: {}",
                        symbol, slId, positionKey, e.getMessage());
                TelegramAlert.sendAsync("Exit blocked: SL cancel failed for " + symbol);
                return false;
            }
        }

        if (paperMode) return true;
        List<SlOrder> openStopLosses = findOpenStopLossOrders(symbol, token, exchange);
        if (openStopLosses == null) {
            if (trackedSlCancelled) {
                log.warn("Protective SL order book verification unavailable after tracked SL cancel | {} key={}",
                        symbol, positionKey);
                return true;
            }
            log.error("Protective SL verification failed before close | {} key={}",
                    symbol, positionKey);
            TelegramAlert.sendAsync("Exit blocked: could not verify SL order book for " + symbol);
            return false;
        }

        boolean ok = true;
        for (SlOrder sl : openStopLosses) {
            try {
                smartConnect.cancelOrder(sl.orderId(), sl.variety());
                log.info("Protective SL cancelled from order book | {} | orderId:{} | key={}",
                        symbol, sl.orderId(), positionKey);
            } catch (Exception e) {
                ok = false;
                log.error("Protective SL cancel failed from order book | {} orderId:{} key={}: {}",
                        symbol, sl.orderId(), positionKey, e.getMessage());
            }
        }
        if (!ok) TelegramAlert.sendAsync("Exit blocked: protective SL cancel failed for " + symbol);
        return ok;
    }

    private boolean hasTrackedPositionForSymbol(String symbol, String candidatePositionKey) {
        String target = underlyingKey(symbol);
        if (target.isBlank()) return false;
        return openPositions.entrySet().stream()
                .filter(e -> !e.getKey().equals(candidatePositionKey))
                .map(e -> e.getValue().symbol())
                .map(OrderManager::underlyingKey)
                .anyMatch(target::equals);
    }

    private List<SlOrder> findOpenStopLossOrders(String symbol, String token, String exchange) {
        JSONObject orderBook;
        try {
            orderBook = getOrderBook();
        } catch (Exception e) {
            log.warn("Order book lookup failed for protective SL | {} [{}]: {}",
                    symbol, token, e.getMessage());
            return null;
        }

        JSONArray rows = extractOrderRows(orderBook);
        if (rows == null) return null;

        List<SlOrder> orders = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !matchesPosition(row, symbol, token, exchange)) continue;

            String variety = firstString(row, "variety");
            String orderType = firstString(row, "ordertype", "orderType");
            if (!isStopLossOrder(variety, orderType)) continue;

            String status = firstString(row, "status", "orderstatus", "orderStatus").toLowerCase();
            if (!isOpenOrderStatus(status)) continue;

            String orderId = firstString(row, "orderid", "orderId", "order_id");
            if (isBlank(orderId)) continue;

            orders.add(new SlOrder(
                    orderId,
                    firstString(row, "tradingsymbol", "symbol", "symbolname"),
                    firstString(row, "symboltoken", "token"),
                    firstString(row, "exchange"),
                    isBlank(variety) ? "STOPLOSS" : variety));
        }
        return orders;
    }

    private JSONObject getOrderBook() throws Exception {
        java.lang.reflect.Field routesField = SmartConnect.class.getDeclaredField("routes");
        routesField.setAccessible(true);
        Object routes = routesField.get(smartConnect);
        String route = (String) routes.getClass()
                .getMethod("get", String.class)
                .invoke(routes, "api.order.book");

        java.lang.reflect.Field handlerField = SmartConnect.class.getDeclaredField("smartAPIRequestHandler");
        handlerField.setAccessible(true);
        Object handler = handlerField.get(smartConnect);
        return (JSONObject) handler.getClass()
                .getMethod("getRequest", String.class, String.class, String.class)
                .invoke(handler, smartConnect.getApiKey(), route, smartConnect.getAccessToken());
    }

    private static JSONArray extractOrderRows(JSONObject resp) {
        if (resp == null) return null;
        JSONArray data = resp.optJSONArray("data");
        if (data != null) return data;

        JSONObject dataObj = resp.optJSONObject("data");
        if (dataObj == null) return null;
        JSONArray orders = dataObj.optJSONArray("orders");
        if (orders != null) return orders;
        return dataObj.optJSONArray("orderBook");
    }

    private static boolean isStopLossOrder(String variety, String orderType) {
        return "STOPLOSS".equalsIgnoreCase(variety)
                || "STOPLOSS_MARKET".equalsIgnoreCase(orderType)
                || "STOPLOSS_LIMIT".equalsIgnoreCase(orderType);
    }

    private static boolean isOpenOrderStatus(String status) {
        if (isBlank(status)) return true;
        return !(status.contains("complete")
                || status.contains("cancel")
                || status.contains("reject")
                || status.contains("fail"));
    }

    private static boolean isCompleteStatus(String status) {
        return status != null && status.toLowerCase().contains("complete");
    }

    private static boolean isTerminalUnfilledStatus(String status) {
        if (status == null) return false;
        String s = status.toLowerCase();
        return s.contains("cancel")
                || s.contains("reject")
                || s.contains("fail");
    }

    private boolean isBrokerFlatEntryCooldownActive(String side, String symbol) {
        String key = underlyingKey(symbol);
        if (key.isBlank()) return false;

        Long until = brokerFlatEntryBlockedUntil.get(key);
        if (until == null) return false;

        long now = System.currentTimeMillis();
        if (until <= now) {
            brokerFlatEntryBlockedUntil.remove(key, until);
            return false;
        }

        long remainingSeconds = Math.max(1, (until - now + 999) / 1000);
        log.warn("{} blocked - broker-flat sync cooldown active | {} key={} remaining={}s",
                side, symbol, key, remainingSeconds);
        return true;
    }

    private boolean hasBrokerPositionForSymbol(String symbol, String token, String exchange) {
        BrokerPosition exact = fetchBrokerPosition(symbol, token, exchange);
        if (exact != null && !exact.isFlat() && !isDeliveryProductType(exact.productType())) return true;

        String target = underlyingKey(symbol);
        if (target.isBlank()) return false;

        JSONObject resp = brokerPositions();
        JSONArray positions = extractPositions(resp);
        if (positions == null) return false;

        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.optJSONObject(i);
            if (row == null || positionNetQty(row) == 0) continue;
            if (isDeliveryProductType(brokerProductType(row))) continue;

            String rowSymbol = firstString(row, "tradingsymbol", "symbol", "symbolname");
            String rowToken = firstString(row, "symboltoken", "token");
            String rowExchange = firstString(row, "exchange");
            if ((!isBlank(token) && token.equals(rowToken)) || target.equals(underlyingKey(rowSymbol))) {
                log.warn("Broker position match | requested:{} [{}] existing:{} [{}] exchange:{}",
                        symbol, token, rowSymbol, rowToken, rowExchange);
                return true;
            }
        }
        return false;
    }

    private int brokerOpenQtyForSide(String symbol, String token, String exchange, String openingSide) {
        try {
            invalidateBrokerPositionCache();
            BrokerPosition brokerPosition = fetchBrokerPosition(symbol, token, exchange);
            if (brokerPosition == null || brokerPosition.isFlat()) return 0;
            if (!brokerPosition.matchesOpeningSide(openingSide)) {
                log.error("Broker position side mismatch after entry order | {} | expected:{} | brokerNetQty:{}",
                        symbol, openingSide, brokerPosition.netQty());
                return 0;
            }
            return brokerPosition.absQty();
        } catch (Exception e) {
            log.error("Broker position verification failed after entry order | {} | {}", symbol, e.getMessage());
            return 0;
        }
    }

    private void flattenPartialEntry(String symbol, String token, String exchange,
                                     String openingSide, int filledQty, String entryOrderId,
                                     String orderProductType) {
        String flattenSide = oppositeOf(openingSide);
        log.error("PARTIAL ENTRY FILL detected after timeout | orderId:{} | {} | filledQty:{} | flattenSide:{}",
                entryOrderId, symbol, filledQty, flattenSide);
        TelegramAlert.sendAsync("CRITICAL: partial entry fill flattened | " + symbol
                + " qty:" + filledQty + " order:" + entryOrderId);
        try {
            String flattenOrderId = placeMarketOrder(symbol, token, exchange,
                    filledQty, flattenSide, orderProductType);
            invalidateBrokerPositionCache();
            log.error("PARTIAL ENTRY flattened | entryOrderId:{} | flattenOrderId:{} | {} x{} | side:{}",
                    entryOrderId, flattenOrderId, symbol, filledQty, flattenSide);
        } catch (Exception e) {
            log.error("CRITICAL: partial entry flatten failed | entryOrderId:{} | {} | qty:{} | side:{} | {}",
                    entryOrderId, symbol, filledQty, flattenSide, e.getMessage());
            TelegramAlert.sendAsync("CRITICAL: partial entry flatten FAILED | " + symbol
                    + " qty:" + filledQty + " side:" + flattenSide + " | check broker NOW");
        }
    }

    private BrokerPosition fetchBrokerPosition(String symbol, String token, String exchange) {
        if (smartConnect == null) {
            throw new IllegalStateException("SmartConnect not initialised");
        }

        JSONObject resp = brokerPositions();
        if (resp == null) {
            throw new IllegalStateException("null response from getPosition");
        }
        if (resp.has("status") && !resp.optBoolean("status", true)) {
            throw new IllegalStateException("getPosition status=false: " + resp.optString("message"));
        }

        JSONArray positions = extractPositions(resp);
        if (positions == null) return null;

        BrokerPosition bestMatch = null;
        for (int i = 0; i < positions.length(); i++) {
            JSONObject row = positions.optJSONObject(i);
            if (row == null || !matchesPosition(row, symbol, token, exchange)) continue;

            BrokerPosition candidate = new BrokerPosition(
                    positionNetQty(row),
                    firstString(row, "tradingsymbol", "symbol", "symbolname"),
                    firstString(row, "symboltoken", "token"),
                    firstString(row, "exchange"),
                    brokerProductType(row));
            if (!candidate.isFlat()) return candidate;
            bestMatch = candidate;
        }
        return bestMatch;
    }

    private JSONObject brokerPositions() {
        long now = System.currentTimeMillis();
        JSONObject cached = cachedBrokerPositions;
        if (cached != null && now < cachedBrokerPositionsUntil) {
            return cached;
        }
        JSONObject fresh = smartConnect.getPosition();
        cachedBrokerPositions = fresh;
        cachedBrokerPositionsUntil = now + BROKER_POSITION_CACHE_MS;
        return fresh;
    }

    private void invalidateBrokerPositionCache() {
        cachedBrokerPositions = null;
        cachedBrokerPositionsUntil = 0;
    }

    private void syncRiskManagedOpenPositionCount() {
        riskManager.syncOpenPositions(getRiskManagedOpenPositionCount());
    }

    private static JSONArray extractPositions(JSONObject resp) {
        if (resp == null) return null;
        JSONArray directData = resp.optJSONArray("data");
        if (directData != null) return directData;

        JSONObject data = resp.optJSONObject("data");
        if (data == null) return null;
        JSONArray positions = data.optJSONArray("positions");
        if (positions != null) return positions;
        positions = data.optJSONArray("position");
        if (positions != null) return positions;
        return data.optJSONArray("net");
    }

    private static boolean matchesPosition(JSONObject row, String symbol, String token, String exchange) {
        String rowToken = firstString(row, "symboltoken", "token");
        String rowSymbol = firstString(row, "tradingsymbol", "symbol", "symbolname");
        String rowExchange = firstString(row, "exchange");

        boolean tokenMatches = !isBlank(rowToken) && rowToken.equals(token);
        boolean symbolMatches = !isBlank(rowSymbol) && rowSymbol.equalsIgnoreCase(symbol);
        boolean exchangeMatches = isBlank(rowExchange) || rowExchange.equalsIgnoreCase(exchange);

        return (tokenMatches || symbolMatches) && exchangeMatches;
    }

    private static int positionNetQty(JSONObject row) {
        Integer netQty = firstInt(row, "netqty", "netQty", "net_quantity");
        if (netQty != null) return netQty;

        Integer buyQtyValue = firstInt(row, "buyqty", "buyQty", "totalbuyqty", "totalBuyQty", "cfbuyqty");
        Integer sellQtyValue = firstInt(row, "sellqty", "sellQty", "totalsellqty", "totalSellQty", "cfsellqty");
        int buyQty = buyQtyValue == null ? 0 : buyQtyValue;
        int sellQty = sellQtyValue == null ? 0 : sellQtyValue;
        return buyQty - sellQty;
    }

    private static Integer firstInt(JSONObject row, String... keys) {
        for (String key : keys) {
            if (!row.has(key) || row.isNull(key)) continue;
            Object value = row.opt(key);
            if (value instanceof Number number) return number.intValue();
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                try {
                    return (int) Math.round(Double.parseDouble(text));
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return null;
    }

    private static double firstPositiveDouble(JSONObject row, String... keys) {
        for (String key : keys) {
            if (!row.has(key) || row.isNull(key)) continue;
            Object value = row.opt(key);
            if (value instanceof Number number && number.doubleValue() > 0) {
                return number.doubleValue();
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                try {
                    double parsed = Double.parseDouble(text);
                    if (parsed > 0) return parsed;
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return -1;
    }

    private static String firstString(JSONObject row, String... keys) {
        for (String key : keys) {
            String value = row.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static boolean isRiskManagedProductType(String value) {
        return !isDeliveryProductType(value);
    }

    private static boolean isDeliveryProductType(String value) {
        if (value == null) return false;
        String normalized = value.trim().toUpperCase();
        return "DELIVERY".equals(normalized)
                || "CNC".equals(normalized)
                || "CASHNCARRY".equals(normalized);
    }

    private static String brokerProductType(JSONObject row) {
        return firstString(row,
                "producttype", "productType", "product", "prd", "pcode", "holdingtype", "holdingType");
    }

    private static String oppositeOf(String side) {
        return "BUY".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private String normalizeProductType(String value) {
        if (value == null || value.isBlank()) return productType;
        return value.trim().toUpperCase();
    }

    private static String underlyingKey(String value) {
        if (value == null) return "";
        String cleaned = value.trim().toUpperCase()
                .replace("-EQ", "")
                .replace("-BE", "")
                .replace("-SM", "");
        cleaned = cleaned.replaceAll("\\d{2}[A-Z]{3}\\d{2,4}FUT$", "");
        cleaned = cleaned.replaceAll("\\d{2}[A-Z]{3}\\d{2}\\d+(CE|PE)$", "");
        if (cleaned.endsWith("FUT")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
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
