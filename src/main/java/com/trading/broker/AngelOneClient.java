package com.trading.broker;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import com.trading.config.AppConfig;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
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

    private String generateTotp() throws Exception {
        var generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long timeStep = new SystemTimeProvider().getTime() / 30;
        return generator.generate(totpSecret, timeStep);
    }
}
