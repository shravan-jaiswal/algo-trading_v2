package com.trading.notification;

import com.trading.execution.OrderManager;
import com.trading.risk.RiskManager;
import com.trading.utils.Shared;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls Telegram for incoming commands and routes them to the engine.
 * Supported: /status, /pnl, /positions, /halt, /resume, /closeall, /restart, /help
 */
public class TelegramCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandListener.class);

    private final String       botToken;
    private final String       chatId;
    private final RiskManager  riskManager;
    private final OrderManager orderManager;
    private final Runnable     closeAllCallback;
    private final Runnable     restartCallback;
    private final List<String> strategyNames;
    private final OkHttpClient http = new OkHttpClient();

    private long lastUpdateId = 0;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("telegram-listener").factory());

    public TelegramCommandListener(String botToken, String chatId,
                                   RiskManager riskManager,
                                   OrderManager orderManager,
                                   Runnable closeAllCallback,
                                   Runnable restartCallback,
                                   List<String> strategyNames) {
        this.botToken         = botToken;
        this.chatId           = chatId;
        this.riskManager      = riskManager;
        this.orderManager     = orderManager;
        this.closeAllCallback = closeAllCallback;
        this.restartCallback  = restartCallback;
        this.strategyNames    = strategyNames;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::poll, 5, 5, TimeUnit.SECONDS);
        log.info("TelegramCommandListener started.");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void poll() {
        try {
            String url = "https://api.telegram.org/bot" + botToken +
                         "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=2";
            Request req = new Request.Builder().url(url).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                JSONObject json    = new JSONObject(resp.body().string());
                JSONArray  updates = json.optJSONArray("result");
                if (updates == null) return;

                for (int i = 0; i < updates.length(); i++) {
                    JSONObject upd = updates.getJSONObject(i);
                    lastUpdateId = upd.getLong("update_id");
                    handleUpdate(upd);
                }
            }
        } catch (IOException e) {
            log.warn("Telegram poll error: {}", e.getMessage());
        }
    }

    private void handleUpdate(JSONObject upd) {
        JSONObject msg = upd.optJSONObject("message");
        if (msg == null) return;

        String senderChatId = String.valueOf(msg.optJSONObject("chat") != null
                ? msg.getJSONObject("chat").getLong("id") : 0);

        String text = msg.optString("text", "").trim().toLowerCase();

        String reply = switch (text) {
            case "/status"    -> buildStatus();
            case "/pnl"       -> buildPnl();
            case "/positions" -> buildPositions();
            case "/halt"      -> { riskManager.haltTrading();  yield "⛔ Trading HALTED. No new entries."; }
            case "/resume"    -> { riskManager.resumeTrading(); yield "✅ Trading RESUMED."; }
            case "/closeall"  -> {
                if (closeAllCallback != null) {
                    Thread.ofVirtual().start(closeAllCallback);
                    yield "🔴 Closing all positions...";
                }
                yield "closeall not available.";
            }
            case "/restart"   -> {
                if (restartCallback != null) {
                    Thread.ofVirtual().start(restartCallback);
                    yield "Restart requested. Service will come back up shortly.";
                }
                yield "restart not available.";
            }
            case "/strategy"  -> buildStrategy();
            case "/help"      -> buildHelp();
            default           -> null;
        };

        if (reply != null) TelegramAlert.sendAsyncTo(senderChatId, reply);
    }

    private String buildStatus() {
        return String.format(
            "📊 Status\n" +
            "Mode     : %s\n" +
            "Open     : %d positions\n" +
            "Today    : %d trades\n" +
            "Deployed : Rs.%s\n" +
            "Available: Rs.%s\n" +
            "Halted   : %s",
            riskManager.isHalted() ? "HALTED" : "ACTIVE",
            riskManager.getOpenPositions(),
            riskManager.getTradesToday(),
            Shared.fmt(riskManager.getDeployedCapital()),
            Shared.fmt(riskManager.getAvailableCapital()),
            riskManager.isHalted() ? "YES ⛔" : "NO ✅"
        );
    }

    private String buildPnl() {
        double net = riskManager.getDailyProfit() - riskManager.getDailyLoss();
        return String.format(
            "💰 P&L Today\n" +
            "Profit : Rs.%s\n" +
            "Loss   : Rs.%s\n" +
            "Net    : Rs.%s",
            Shared.fmt(riskManager.getDailyProfit()),
            Shared.fmt(riskManager.getDailyLoss()),
            Shared.fmtPnl(net)
        );
    }

    private String buildPositions() {
        Map<String, OrderManager.TrackedPosition> positions = orderManager.getOpenPositions();
        if (positions.isEmpty()) return "📭 No open positions.";

        StringBuilder sb = new StringBuilder("📈 Open Positions\n");
        positions.forEach((key, p) -> {
            String symbol = p.symbol().isEmpty() ? key : p.symbol();
            String strategy = key.contains("|") ? key.split("\\|")[1] : "";
            sb.append(String.format("• %s [%s] %s x%d @ Rs.%.2f\n",
                    symbol, strategy, p.side(), p.qty(), p.entryPrice()));
        });
        return sb.toString().trim();
    }

    private String buildStrategy() {
        StringBuilder sb = new StringBuilder("📐 Active Strategies\n");
        sb.append("Instrument: ").append(com.trading.config.AppConfig.get("trading.instrument.type", "EQUITY")).append("\n");
        strategyNames.forEach(n -> sb.append("• ").append(n).append("\n"));
        return sb.toString().trim();
    }

    private String buildHelp() {
        return "🤖 Commands\n" +
               "/status    — system status\n" +
               "/pnl       — today's P&L\n" +
               "/positions — open positions\n" +
               "/strategy  — active strategies\n" +
               "/halt      — stop new trades\n" +
               "/resume    — resume trading\n" +
               "/closeall  — close all positions\n" +
               "/restart   — restart the service\n" +
               "/help      — this message";
    }
}
