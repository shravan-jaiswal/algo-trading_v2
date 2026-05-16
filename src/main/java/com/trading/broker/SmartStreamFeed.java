package com.trading.broker;

import com.angelbroking.smartapi.smartstream.models.*;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamListener;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamTicker;
import org.json.JSONObject;
import com.trading.config.AppConfig;
import com.trading.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Subscribes to Angel One SmartStream V3 for real-time price ticks.
 */
public class SmartStreamFeed {

    private static final Logger log = LoggerFactory.getLogger(SmartStreamFeed.class);

    private static final double       PRICE_SCALE = Double.parseDouble(
            AppConfig.get("angel.smartstream.price.scale", "100"));
    private static final SmartStreamSubsMode SUBS_MODE = parseSubsMode(
            AppConfig.get("feed.mode", "LTP"));

    private final String         clientId;
    private final String         feedToken;
    private final Consumer<Tick> tickHandler;

    private SmartStreamTicker ticker;
    private volatile boolean  connected = false;
    private long              lastTickMs = 0;

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
    public void subscribe(List<String> tokens, String exchange) {
        try {
            ExchangeType exType = "NFO".equalsIgnoreCase(exchange)
                    ? ExchangeType.NSE_FO : ExchangeType.NSE_CM;
            Set<TokenID> tokenSet = new HashSet<>();
            for (String t : tokens) {
                tokenSet.add(new TokenID(exType, t));
            }

            SmartStreamListener listener = new SmartStreamListener() {
                @Override
                public void onLTPArrival(LTP ltp) {
                    if (ltp == null || ltp.getToken() == null) return;
                    lastTickMs = System.currentTimeMillis();
                    double price = ltp.getLastTradedPrice() / PRICE_SCALE;
                    if (price > 0) {
                        tickHandler.accept(Tick.ltpOnly(
                                ltp.getToken().getToken(), price));
                    }
                }

                @Override
                public void onQuoteArrival(Quote quote) {
                    if (quote == null || quote.getToken() == null) return;
                    lastTickMs = System.currentTimeMillis();
                    double price  = quote.getLastTradedPrice() / PRICE_SCALE;
                    double volume = quote.getLastTradedQty();
                    if (price > 0) {
                        tickHandler.accept(Tick.withVolume(
                                quote.getToken().getToken(), price, volume));
                    }
                }

                @Override
                public void onSnapQuoteArrival(SnapQuote snap) {}

                @Override
                public void onDepthArrival(Depth depth) {}

                @Override
                public SmartStreamError onErrorCustom() { return null; }

                @Override
                public void onConnected() {
                    connected = true;
                    log.info("SmartStream connected — subscribing {} tokens", tokens.size());
                    try {
                        ticker.subscribe(SUBS_MODE, tokenSet);
                    } catch (Exception e) {
                        log.error("SmartStream subscription failed: {}", e.getMessage());
                    }
                }

                @Override
                public void onDisconnected() {
                    connected = false;
                    log.warn("SmartStream disconnected.");
                }

                @Override
                public void onError(SmartStreamError error) {
                    Throwable ex = error != null ? error.getException() : null;
                    log.error("SmartStream error: {}", ex != null ? ex.getMessage() : "unknown");
                }

                @Override
                public void onPong() {}
            };

            if (connected && ticker != null) {
                // Feed already running — add tokens without reconnecting
                ticker.subscribe(SUBS_MODE, tokenSet);
                log.info("SmartStream added {} {} tokens to existing feed.", tokens.size(), exchange);
            } else {
                ticker = new SmartStreamTicker(clientId, feedToken, listener);
                ticker.connect();
                log.info("SmartStream feed started.");
            }

        } catch (Exception e) {
            log.error("SmartStream subscribe failed: {}", e.getMessage(), e);
        }
    }

    public void disconnect() {
        if (ticker != null) {
            try { ticker.disconnect(); } catch (Exception ignored) {}
        }
    }

    public boolean isConnected()          { return connected; }
    public boolean isStale(long threshold) {
        return lastTickMs > 0 && (System.currentTimeMillis() - lastTickMs) > threshold;
    }

    private static SmartStreamSubsMode parseSubsMode(String mode) {
        return switch (mode.toUpperCase()) {
            case "QUOTE"      -> SmartStreamSubsMode.QUOTE;
            case "SNAP_QUOTE" -> SmartStreamSubsMode.SNAP_QUOTE;
            default           -> SmartStreamSubsMode.LTP;
        };
    }
}
