package com.trading.diagnostics;

import com.sun.net.httpserver.HttpServer;
import com.trading.config.AppConfig;
import com.trading.execution.OrderManager;
import com.trading.risk.RiskManager;
import com.trading.utils.Shared;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Lightweight HTTP health + status endpoint.
 * Exposes: GET /health  → JSON risk snapshot
 *          GET /status  → same
 */
public class TradingDashboard {

    private static final Logger log = LoggerFactory.getLogger(TradingDashboard.class);

    private final RiskManager  riskManager;
    private final OrderManager orderManager;
    private final Supplier<JSONObject> runtimeSnapshotSupplier;
    private       HttpServer   server;

    public TradingDashboard(RiskManager riskManager, OrderManager orderManager) {
        this(riskManager, orderManager, null);
    }

    public TradingDashboard(RiskManager riskManager, OrderManager orderManager,
                            Supplier<JSONObject> runtimeSnapshotSupplier) {
        this.riskManager  = riskManager;
        this.orderManager = orderManager;
        this.runtimeSnapshotSupplier = runtimeSnapshotSupplier;
    }

    public void start() {
        int port = AppConfig.getInt("health.port", 8080);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/health", this::handleRequest);
            server.createContext("/status", this::handleRequest);
            server.createContext("/replay/today", this::handleReplayToday);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            log.info("TradingDashboard started on port {}", port);
        } catch (IOException e) {
            log.warn("TradingDashboard failed to start: {}", e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handleRequest(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        JSONObject json = buildSnapshot();
        writeJson(ex, 200, json);
    }

    private void handleReplayToday(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String strategy = query.getOrDefault("strategy", "MICS");
            String timeframe = query.getOrDefault("timeframe", "FIVE_MINUTE");
            String token = query.get("token");
            JSONObject json = TodayReplayRunner.replay(timeframe, strategy, token);
            writeJson(ex, 200, json);
        } catch (Exception e) {
            writeJson(ex, 500, new JSONObject()
                    .put("error", "replay_failed")
                    .put("message", e.getMessage()));
        }
    }

    private void writeJson(com.sun.net.httpserver.HttpExchange ex, int status, JSONObject json)
            throws IOException {
        byte[] body = json.toString(2).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return query;
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            query.put(key, value);
        }
        return query;
    }

    private JSONObject buildSnapshot() {
        JSONObject json = new JSONObject()
            .put("halted",           riskManager.isHalted())
            .put("openPositions",    riskManager.getOpenPositions())
            .put("tradesToday",      riskManager.getTradesToday())
            .put("dailyProfit",      riskManager.getDailyProfit())
            .put("dailyLoss",        riskManager.getDailyLoss())
            .put("netPnl",           riskManager.getDailyProfit() - riskManager.getDailyLoss())
            .put("deployedCapital",  riskManager.getDeployedCapital())
            .put("availableCapital", riskManager.getAvailableCapital());
        if (runtimeSnapshotSupplier != null) {
            json.put("runtime", runtimeSnapshotSupplier.get());
        }
        return json;
    }
}
