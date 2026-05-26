package com.trading.broker;

import com.angelbroking.smartapi.smartstream.models.Depth;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.smartstream.models.LTP;
import com.angelbroking.smartapi.smartstream.models.Quote;
import com.angelbroking.smartapi.smartstream.models.SmartStreamError;
import com.angelbroking.smartapi.smartstream.models.SmartStreamSubsMode;
import com.angelbroking.smartapi.smartstream.models.SnapQuote;
import com.angelbroking.smartapi.smartstream.models.TokenID;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamListener;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamTicker;
import com.trading.config.AppConfig;
import com.trading.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Subscribes to Angel One SmartStream V3 for real-time price ticks.
 */
public class SmartStreamFeed {

    private static final Logger log = LoggerFactory.getLogger(SmartStreamFeed.class);

    private static final double PRICE_SCALE = Double.parseDouble(
            AppConfig.get("angel.smartstream.price.scale", "100"));
    private static final SmartStreamSubsMode SUBS_MODE = parseSubsMode(
            AppConfig.get("feed.mode", "LTP"));

    private final String         clientId;
    private final String         feedToken;
    private final Consumer<Tick> tickHandler;

    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("smartstream-reconnect").factory());
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private SmartStreamTicker ticker;
    private volatile boolean  connected = false;
    private volatile boolean  shuttingDown = false;
    private volatile int      reconnectAttempts = 0;
    private volatile long     lastTickMs = 0;

    public SmartStreamFeed(com.angelbroking.smartapi.SmartConnect smartConnect,
                           String clientId, String feedToken,
                           Consumer<Tick> tickHandler) {
        this.clientId    = clientId;
        this.feedToken   = feedToken;
        this.tickHandler = tickHandler;
    }

    /**
     * @param exchange "NSE" for equity, "NFO" for options/futures
     */
    public synchronized void subscribe(List<String> tokens, String exchange) {
        if (tokens == null || tokens.isEmpty()) return;

        shuttingDown = false;
        String exchangeKey = "NFO".equalsIgnoreCase(exchange) ? "NFO" : "NSE";
        Set<String> storedTokens = subscriptions.computeIfAbsent(exchangeKey, k -> ConcurrentHashMap.newKeySet());
        List<String> newTokens = tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .filter(storedTokens::add)
                .toList();
        if (newTokens.isEmpty() && connected && ticker != null) {
            log.debug("SmartStream subscription already active for {} {} token(s).",
                    tokens.size(), exchangeKey);
            return;
        }

        try {
            SmartStreamListener listener = listener();

            if (connected && ticker != null) {
                Set<TokenID> tokenSet = tokenSet(newTokens, exchangeKey);
                ticker.subscribe(SUBS_MODE, tokenSet);
                log.info("SmartStream added {} new {} tokens to existing feed.", newTokens.size(), exchangeKey);
            } else {
                connect(listener);
            }
        } catch (Exception e) {
            log.error("SmartStream subscribe failed: {}", e.getMessage(), e);
            scheduleReconnect("subscribe_failed");
        }
    }

    public synchronized void disconnect() {
        shuttingDown = true;
        reconnectScheduler.shutdownNow();
        connected = false;
        if (ticker != null) {
            try { ticker.disconnect(); } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() { return connected; }

    public boolean isStale(long threshold) {
        return lastTickMs > 0 && (System.currentTimeMillis() - lastTickMs) > threshold;
    }

    public long lastTickAgeMs() {
        return lastTickMs > 0 ? System.currentTimeMillis() - lastTickMs : Long.MAX_VALUE;
    }

    public void reconnectNow(String reason) {
        if (shuttingDown) return;
        log.warn("SmartStream reconnect requested | reason={}", reason);
        reconnectScheduler.execute(this::reconnect);
    }

    private SmartStreamListener listener() {
        return new SmartStreamListener() {
            @Override
            public void onLTPArrival(LTP ltp) {
                if (ltp == null || ltp.getToken() == null) return;
                lastTickMs = System.currentTimeMillis();
                double price = ltp.getLastTradedPrice() / PRICE_SCALE;
                if (price > 0) {
                    tickHandler.accept(Tick.ltpOnly(ltp.getToken().getToken(), price));
                }
            }

            @Override
            public void onQuoteArrival(Quote quote) {
                if (quote == null || quote.getToken() == null) return;
                lastTickMs = System.currentTimeMillis();
                double price  = quote.getLastTradedPrice() / PRICE_SCALE;
                double volume = quote.getLastTradedQty();
                if (price > 0) {
                    tickHandler.accept(Tick.withVolume(quote.getToken().getToken(), price, volume));
                }
            }

            @Override public void onSnapQuoteArrival(SnapQuote snap) {}
            @Override public void onDepthArrival(Depth depth) {}
            @Override public SmartStreamError onErrorCustom() { return null; }
            @Override public void onPong() {}

            @Override
            public void onConnected() {
                connected = true;
                reconnectAttempts = 0;
                log.info("SmartStream connected - subscribing {} tokens", totalSubscriptionCount());
                try {
                    subscribeAll();
                } catch (Exception e) {
                    log.error("SmartStream subscription failed: {}", e.getMessage(), e);
                    scheduleReconnect("subscription_failed");
                }
            }

            @Override
            public void onDisconnected() {
                connected = false;
                log.warn("SmartStream disconnected.");
                scheduleReconnect("disconnected");
            }

            @Override
            public void onError(SmartStreamError error) {
                Throwable ex = error != null ? error.getException() : null;
                log.error("SmartStream error: {}", ex != null ? ex.getMessage() : "unknown");
                scheduleReconnect("error");
            }
        };
    }

    private synchronized void connect(SmartStreamListener listener) {
        if (shuttingDown) return;
        try {
            ticker = new SmartStreamTicker(clientId, feedToken, listener);
            ticker.connect();
            log.info("SmartStream feed started.");
        } catch (Exception e) {
            log.error("SmartStream connect failed: {}", e.getMessage(), e);
            scheduleReconnect("connect_failed");
        }
    }

    private void scheduleReconnect(String reason) {
        if (shuttingDown) return;
        if (!reconnectScheduled.compareAndSet(false, true)) return;

        int attempt = ++reconnectAttempts;
        long delaySeconds = Math.min(60, Math.max(5, attempt * 5L));
        log.warn("SmartStream reconnect scheduled in {}s | reason={} attempt={}",
                delaySeconds, reason, attempt);

        reconnectScheduler.schedule(() -> {
            reconnectScheduled.set(false);
            reconnect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private synchronized void reconnect() {
        if (shuttingDown || subscriptions.isEmpty()) return;
        try {
            connected = false;
            if (ticker != null) {
                try { ticker.disconnect(); } catch (Exception ignored) {}
                ticker = null;
            }

            Set<String> nse = subscriptions.getOrDefault("NSE", Set.of());
            Set<String> nfo = subscriptions.getOrDefault("NFO", Set.of());
            log.info("SmartStream reconnecting | NSE={} NFO={}", nse.size(), nfo.size());

            connect(listener());
        } catch (Exception e) {
            log.error("SmartStream reconnect failed: {}", e.getMessage(), e);
            scheduleReconnect("reconnect_failed");
        }
    }

    private void subscribeAll() {
        subscribeStored("NSE", ExchangeType.NSE_CM);
        subscribeStored("NFO", ExchangeType.NSE_FO);
    }

    private void subscribeStored(String exchange, ExchangeType exType) {
        Set<String> tokens = subscriptions.get(exchange);
        if (tokens == null || tokens.isEmpty() || ticker == null) return;
        Set<TokenID> tokenSet = new HashSet<>();
        for (String token : tokens) {
            tokenSet.add(new TokenID(exType, token));
        }
        ticker.subscribe(SUBS_MODE, tokenSet);
        log.info("SmartStream subscribed {} {} tokens.", tokenSet.size(), exchange);
    }

    private static Set<TokenID> tokenSet(List<String> tokens, String exchange) {
        ExchangeType exType = "NFO".equalsIgnoreCase(exchange)
                ? ExchangeType.NSE_FO : ExchangeType.NSE_CM;
        Set<TokenID> tokenSet = new HashSet<>();
        for (String token : tokens) {
            tokenSet.add(new TokenID(exType, token));
        }
        return tokenSet;
    }

    private int totalSubscriptionCount() {
        return subscriptions.values().stream().mapToInt(Set::size).sum();
    }

    private static SmartStreamSubsMode parseSubsMode(String mode) {
        return switch (mode.toUpperCase()) {
            case "QUOTE"      -> SmartStreamSubsMode.QUOTE;
            case "SNAP_QUOTE" -> SmartStreamSubsMode.SNAP_QUOTE;
            default           -> SmartStreamSubsMode.LTP;
        };
    }
}
