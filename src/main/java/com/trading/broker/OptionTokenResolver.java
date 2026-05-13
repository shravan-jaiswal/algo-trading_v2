package com.trading.broker;

import com.angelbroking.smartapi.SmartConnect;
import com.trading.strategy.InstrumentConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves option instrument tokens from the Angel One instrument master.
 * Finds the nearest ATM/OTM option token for a given underlying at a given price.
 */
public class OptionTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(OptionTokenResolver.class);

    private final SmartConnect smartConnect;

    // Cache: underlying → instruments list (loaded once per day)
    private final Map<String, JSONArray> instrumentCache = new HashMap<>();

    public OptionTokenResolver(SmartConnect smartConnect) {
        this.smartConnect = smartConnect;
    }

    /** Carries resolved option token + lot size from the broker instrument master. */
    public record OptionResolution(String token, int lotSize) {}

    /**
     * Resolves the token and lot size for an option (CE or PE) on the given underlying.
     *
     * @param underlying  e.g. "NIFTY", "BANKNIFTY", "RELIANCE"
     * @param ltp         current underlying price - used to find ATM strike
     * @param optionType  "CE" or "PE"
     * @param config      contains expiryOffset and strikeOffset
     * @return OptionResolution with token and lot size, or null if not found
     */
    public OptionResolution resolve(String underlying, double ltp,
                                    String optionType, InstrumentConfig config) {
        try {
            JSONArray instruments = getInstruments(underlying);
            if (instruments == null) return null;

            double strikeInterval = detectStrikeInterval(underlying, ltp);
            double atmStrike      = Math.round(ltp / strikeInterval) * strikeInterval;
            double targetStrike   = atmStrike + config.strikeOffset() * strikeInterval;

            LocalDate expiry = resolveExpiry(instruments, underlying, config.expiryOffset());
            if (expiry == null) return null;

            String expiryStr = expiry.toString();

            for (int i = 0; i < instruments.length(); i++) {
                JSONObject inst = instruments.getJSONObject(i);
                String sym = inst.optString("symbol", "");
                if (sym.contains(underlying)
                        && sym.contains(optionType)
                        && inst.optDouble("strike") == targetStrike
                        && inst.optString("expiry", "").equals(expiryStr)) {
                    String token  = inst.optString("token");
                    int    lotSz  = inst.optInt("lotsize", inst.optInt("lot_size", 1));
                    log.info("Option resolved | {} {} strike={} expiry={} token={} lotSize={}",
                            underlying, optionType, targetStrike, expiryStr, token, lotSz);
                    return new OptionResolution(token, lotSz);
                }
            }

        } catch (Exception e) {
            log.error("Option token resolution failed for {} {}: {}", underlying, optionType, e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the nearest futures contract token for the given underlying.
     *
     * @param underlying   e.g. "NIFTY", "RELIANCE"
     * @param expiryOffset 0 = nearest expiry, 1 = next month
     * @return OptionResolution with token and lot size, or null if not found
     */
    public OptionResolution resolveFutures(String underlying, int expiryOffset) {
        try {
            JSONArray instruments = getInstruments(underlying);
            if (instruments == null) return null;

            LocalDate expiry = resolveExpiry(instruments, underlying, expiryOffset);
            if (expiry == null) return null;

            String expiryStr = expiry.toString();

            for (int i = 0; i < instruments.length(); i++) {
                JSONObject inst = instruments.getJSONObject(i);
                String sym        = inst.optString("symbol", "");
                String instrType  = inst.optString("instrumenttype", inst.optString("instrument_type", ""));
                if (sym.contains(underlying)
                        && (instrType.equalsIgnoreCase("FUTSTK")
                            || instrType.equalsIgnoreCase("FUTIDX")
                            || sym.contains("FUT"))
                        && inst.optString("expiry", "").equals(expiryStr)) {
                    String token = inst.optString("token");
                    int    lotSz = inst.optInt("lotsize", inst.optInt("lot_size", 1));
                    log.info("Futures resolved | {} expiry={} token={} lotSize={}",
                            underlying, expiryStr, token, lotSz);
                    return new OptionResolution(token, lotSz);
                }
            }

        } catch (Exception e) {
            log.error("Futures token resolution failed for {}: {}", underlying, e.getMessage());
        }
        return null;
    }

    private JSONArray getInstruments(String underlying) throws Exception { // NOSONAR
        if (instrumentCache.containsKey(underlying)) {
            return instrumentCache.get(underlying);
        }
        JSONObject params = new JSONObject();
        params.put("exchange", "NFO");
        params.put("searchscrip", underlying);
        String raw;
        try {
            raw = smartConnect.getSearchScrip(params);
        } catch (com.angelbroking.smartapi.http.exceptions.SmartAPIException e) {
            throw new Exception("getSearchScrip failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) return null;
        JSONObject resp = new JSONObject(raw);
        if (!resp.optBoolean("status", false) || !resp.has("data")) return null;
        JSONArray arr = resp.getJSONArray("data");
        instrumentCache.put(underlying, arr);
        return arr;
    }

    private double detectStrikeInterval(String underlying, double ltp) {
        if (underlying.contains("BANKNIFTY")) return 100;
        if (underlying.contains("NIFTY"))     return 50;
        return Math.max(100, Math.round(ltp / 100.0) * 5);
    }

    private LocalDate resolveExpiry(JSONArray instruments, String underlying, int offset) {
        // Extract unique expiry dates, sort, pick the Nth one (offset=0 → nearest)
        java.util.TreeSet<LocalDate> expiries = new java.util.TreeSet<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < instruments.length(); i++) {
            String expiryStr = instruments.getJSONObject(i).optString("expiry", "");
            if (expiryStr.isEmpty()) continue;
            try {
                LocalDate d = LocalDate.parse(expiryStr);
                if (!d.isBefore(today)) expiries.add(d);
            } catch (Exception ignored) {}
        }

        if (expiries.isEmpty()) return null;
        var list = expiries.stream().toList();
        return offset < list.size() ? list.get(offset) : list.get(list.size() - 1);
    }
}
