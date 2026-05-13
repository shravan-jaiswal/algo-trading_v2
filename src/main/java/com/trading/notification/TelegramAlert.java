package com.trading.notification;

import com.trading.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TelegramAlert {

    private static final Logger log = LoggerFactory.getLogger(TelegramAlert.class);
    private static final OkHttpClient http = new OkHttpClient();

    private static final boolean  ENABLED;
    private static final String   BOT_TOKEN;
    private static final String[] CHAT_IDS;

    static {
        ENABLED   = AppConfig.getBool("telegram.enabled",   false);
        BOT_TOKEN = AppConfig.get(   "telegram.bot.token", "");
        String raw = AppConfig.get(  "telegram.chat.id",   "");
        CHAT_IDS  = raw.isEmpty() ? new String[0]
                                  : raw.split("\\s*,\\s*");
    }

    private TelegramAlert() {}

    public static void send(String message) {
        if (!ENABLED || BOT_TOKEN.isEmpty() || CHAT_IDS.length == 0) return;
        for (String chatId : CHAT_IDS) {
            sendTo(chatId, message);
        }
    }

    public static void sendTo(String chatId, String message) {
        if (!ENABLED || BOT_TOKEN.isEmpty() || chatId.isBlank()) return;
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId.trim())
                .add("text",    message)
                .build();
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("Telegram send failed to {}: HTTP {}", chatId, resp.code());
            }
        } catch (IOException e) {
            log.warn("Telegram send error to {}: {}", chatId, e.getMessage());
        }
    }

    public static void sendAsync(String message) {
        if (!ENABLED || BOT_TOKEN.isEmpty()) return;
        Thread.ofVirtual().start(() -> send(message));
    }

    public static void sendAsyncTo(String chatId, String message) {
        if (!ENABLED || BOT_TOKEN.isEmpty()) return;
        Thread.ofVirtual().start(() -> sendTo(chatId, message));
    }
}
