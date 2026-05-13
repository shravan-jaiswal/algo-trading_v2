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
import java.nio.charset.StandardCharsets;

/**
 * Lightweight HTTP health + status endpoint.
 * Exposes: GET /health  → JSON risk snapshot
 *          GET /status  → same
 */
public class TradingDashboard {

    private static final Logger log = LoggerFactory.getLogger(TradingDashboard.class);

    private final RiskManager  riskManager;
    private final OrderManager orderManager;
    private       HttpServer   server;

    public TradingDashboard(RiskManager riskManager, OrderManager orderManager) {
        this.riskManager  = riskManager;
        this.orderManager = orderManager;
    }

    public void start() {
        int port = AppConfig.getInt("health.port", 8080);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/health", this::handleRequest);
            server.createContext("/status", this::handleRequest);
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
        byte[] body = json.toString(2).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private JSONObject buildSnapshot() {
        return new JSONObject()
            .put("halted",           riskManager.isHalted())
            .put("openPositions",    riskManager.getOpenPositions())
            .put("tradesToday",      riskManager.getTradesToday())
            .put("dailyProfit",      riskManager.getDailyProfit())
            .put("dailyLoss",        riskManager.getDailyLoss())
            .put("netPnl",           riskManager.getDailyProfit() - riskManager.getDailyLoss())
            .put("deployedCapital",  riskManager.getDeployedCapital())
            .put("availableCapital", riskManager.getAvailableCapital());
    }
}
