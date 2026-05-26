package com.trading.broker;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.User;
import com.trading.config.AppConfig;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AngelOneClient {

    private static final Logger log = LoggerFactory.getLogger(AngelOneClient.class);

    private final String clientId;
    private final String pin;
    private final String totpSecret;
    private final String apiKey;

    private SmartConnect smartConnect;
    private String       accessToken;
    private String       refreshToken;
    private String       feedToken;

    public AngelOneClient() {
        this.clientId   = AppConfig.get("broker.client.id");
        this.pin        = AppConfig.get("broker.pin");
        this.totpSecret = AppConfig.get("broker.totp.secret");
        this.apiKey     = AppConfig.get("broker.api.key");
    }

    public SmartConnect login() throws Exception {
        smartConnect = new SmartConnect(apiKey);
        String totp  = generateTotp();

        User user = smartConnect.generateSession(clientId, pin, totp);
        if (user == null) {
            throw new RuntimeException("Login failed — null response from Angel One.");
        }

        accessToken  = user.getAccessToken();
        refreshToken = user.getRefreshToken();
        feedToken    = user.getFeedToken();

        smartConnect.setAccessToken(accessToken);
        smartConnect.setUserId(clientId);

        log.info("Angel One login successful | clientId:{}", clientId);
        return smartConnect;
    }

    /** Re-login with a fresh TOTP to refresh session tokens. */
    public void refreshSession() {
        try {
            String totp = generateTotp();
            User user   = smartConnect.generateSession(clientId, pin, totp);
            if (user == null) {
                log.error("Token refresh returned null.");
                return;
            }
            accessToken  = user.getAccessToken();
            refreshToken = user.getRefreshToken();
            feedToken    = user.getFeedToken();
            smartConnect.setAccessToken(accessToken);
            log.info("Session tokens refreshed.");
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
        }
    }

    public void logout() {
        try {
            if (smartConnect != null) {
                smartConnect.logout();
                log.info("Logged out from Angel One.");
            }
        } catch (Exception e) {
            log.warn("Logout error: {}", e.getMessage());
        }
    }

    public SmartConnect getSmartConnect() { return smartConnect; }
    public String       getAccessToken()  { return accessToken; }
    public String       getFeedToken()    { return feedToken; }
    public String       getClientId()     { return clientId; }

    public double getLTP(String exchange, String symbol, String token) {
        if (smartConnect == null) return -1;

        try {
            JSONObject params = new JSONObject();
            params.put("mode", "LTP");
            params.put("exchangeTokens", new JSONObject()
                    .put(exchange, new JSONArray().put(token)));

            JSONObject data = smartConnect.marketData(params);
            if (data != null) {
                JSONArray fetched = data.optJSONArray("fetched");
                if (fetched != null && !fetched.isEmpty()) {
                    double ltp = fetched.getJSONObject(0).optDouble("ltp", -1);
                    if (ltp > 0) return ltp;
                }
                log.warn("getLTP(marketData) nothing fetched | exchange:{} symbol:{} token:{} | unfetched:{}",
                        exchange, symbol, token, data.optJSONArray("unfetched"));
            }
        } catch (Exception e) {
            log.warn("getLTP(marketData) failed | exchange:{} symbol:{} token:{} | {}",
                    exchange, symbol, token, e.getMessage());
        } catch (SmartAPIException e) {
            log.warn("getLTP(marketData) SmartAPIException | exchange:{} symbol:{} token:{} | {}",
                    exchange, symbol, token, e.getMessage());
        }

        try {
            JSONObject resp = smartConnect.getLTP(exchange, symbol, token);
            if (resp == null || !resp.optBoolean("status", false)) {
                log.warn("getLTP(fallback) failed | exchange:{} symbol:{} token:{} | resp:{}",
                        exchange, symbol, token, resp);
                return -1;
            }
            JSONObject data = resp.optJSONObject("data");
            return data == null ? -1 : data.optDouble("ltp", -1);
        } catch (Exception e) {
            log.warn("getLTP(fallback) exception | exchange:{} symbol:{} token:{} | {}",
                    exchange, symbol, token, e.getMessage());
            return -1;
        }
    }

    private String generateTotp() throws Exception {
        var generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long timeStep = new SystemTimeProvider().getTime() / 30;
        return generator.generate(totpSecret, timeStep);
    }
}
