package com.trading.data;

import com.angelbroking.smartapi.SmartConnect;
import com.trading.config.AppConfig;
import com.trading.model.Candle;
import com.trading.model.WatchlistItem;
import com.trading.utils.MarketUtils;
import org.json.JSONArray;
import org.json.JSONObject;
// JSONObject is used for the candleData params
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class HistoricalDataFetcher {

    private static final Logger          log   = LoggerFactory.getLogger(HistoricalDataFetcher.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter REQ_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SmartConnect    smartConnect;
    private final CandleRepository candleRepo;
    private volatile long rateLimitCooldownUntilMs = 0;

    public HistoricalDataFetcher(SmartConnect smartConnect, CandleRepository candleRepo) {
        this.smartConnect = smartConnect;
        this.candleRepo   = candleRepo;
    }

    public void setSmartConnect(SmartConnect sc) { this.smartConnect = sc; }
    public boolean isConnected() { return smartConnect != null; }
    public boolean isRateLimitCoolingDown() {
        return System.currentTimeMillis() < rateLimitCooldownUntilMs;
    }
    public long rateLimitCooldownSeconds() {
        long remainingMs = rateLimitCooldownUntilMs - System.currentTimeMillis();
        return Math.max(0, remainingMs / 1000);
    }

    /**
     * Backfills historical candles for a watchlist item.
     * Fetches {@code days} of history and stores in DB + returns the list.
     */
    public List<Candle> backfill(WatchlistItem item, String timeframe, int days) {
        List<Candle> candles = new ArrayList<>();
        if (smartConnect == null || item == null) return candles;
        if (isRateLimitCoolingDown()) return candles;
        try {
            LocalDate fromDate = LocalDate.now(MarketUtils.IST).minusDays(days);
            LocalDate toDate   = LocalDate.now(MarketUtils.IST);
            LocalDateTime from = fromDate.atTime(MarketUtils.marketOpenTime());
            LocalDateTime to   = toDate.atTime(MarketUtils.marketCloseTime());

            candles = fetchAndStore(item, timeframe, from, to, null);
            log.info("Backfilled {} candles for {} ({})", candles.size(), item.symbol(), timeframe);

        } catch (Exception e) {
            log.error("Backfill failed for {}: {}", item.symbol(), e.getMessage());
            if (isRateLimit(e)) enterRateLimitCooldown();
        }
        return candles;
    }

    /**
     * Backfills a bounded missing range. Used on live startup so a weekend or
     * short outage does not re-request months of history for every symbol.
     */
    public List<Candle> backfillRange(WatchlistItem item, String timeframe,
                                      LocalDateTime from, LocalDateTime to) {
        List<Candle> candles = new ArrayList<>();
        if (smartConnect == null || item == null || from == null || to == null) return candles;
        if (isRateLimitCoolingDown()) return candles;

        LocalDateTime safeFrom = truncateToMinute(from);
        LocalDateTime safeTo = truncateToMinute(to);
        if (safeTo.isBefore(safeFrom)) return candles;

        try {
            candles = fetchAndStore(item, timeframe, safeFrom, safeTo, safeTo);
            log.info("Backfilled {} candles for {} ({}) from {} to {}",
                    candles.size(), item.symbol(), timeframe,
                    safeFrom.format(REQ_FMT), safeTo.format(REQ_FMT));
        } catch (Exception e) {
            log.error("Range backfill failed for {}: {}", item.symbol(), e.getMessage());
            if (isRateLimit(e)) enterRateLimitCooldown();
        }
        return candles;
    }

    /**
     * Refreshes only recently closed candles from Angel historical API.
     * This lets the live strategy loop use broker OHLCV candles even when the
     * WebSocket is connected but not delivering ticks.
     */
    public int refreshRecentClosedCandles(WatchlistItem item, String timeframe,
                                          int barMinutes, int overlapBars) {
        if (smartConnect == null || item == null) return 0;
        if (isRateLimitCoolingDown()) return 0;

        int safeBarMinutes = Math.max(1, barMinutes);
        int safeOverlap = Math.max(1, overlapBars);
        LocalDateTime now = LocalDateTime.now(MarketUtils.IST);
        LocalDateTime currentSlot = truncate(now, safeBarMinutes);
        LocalDateTime lastClosedStart = currentSlot.minusMinutes(safeBarMinutes);

        if (!MarketUtils.isRegularMarketSession(lastClosedStart)) {
            log.debug("Historical live refresh skipped | {} | lastClosed:{} outside regular session",
                    item.symbol(), lastClosedStart);
            return 0;
        }

        LocalDateTime from = lastClosedStart.minusMinutes((long) safeBarMinutes * (safeOverlap - 1));
        LocalDateTime marketOpen = lastClosedStart.toLocalDate().atTime(9, 15);
        if (from.isBefore(marketOpen)) from = marketOpen;

        try {
            return fetchAndStore(item, timeframe, from, lastClosedStart, lastClosedStart).size();
        } catch (Exception e) {
            log.warn("Recent candle refresh failed for {}: {}", item.symbol(), e.getMessage());
            if (isRateLimit(e)) enterRateLimitCooldown();
        }
        return 0;
    }

    private List<Candle> fetchAndStore(WatchlistItem item, String timeframe,
                                       LocalDateTime from, LocalDateTime to,
                                       LocalDateTime maxInclusiveTs) throws Exception {
        List<Candle> candles = new ArrayList<>();
        JSONObject params = new JSONObject();
        params.put("exchange",    item.exchange());
        params.put("symboltoken", item.token());
        params.put("interval",    timeframe);
        params.put("fromdate",    truncateToMinute(from).format(REQ_FMT));
        params.put("todate",      truncateToMinute(to).format(REQ_FMT));

        JSONArray data = smartConnect.candleData(params);
        if (data == null) {
            // SmartAPI sometimes returns the plain-text rate-limit response
            // "Access denied because of exceeding access rate". Its SDK logs a
            // JSON parse error and returns null, so no exception reaches us.
            log.warn("No candle data returned for {} - pausing historical API requests",
                    item.symbol());
            enterRateLimitCooldown();
            return candles;
        }
        for (int i = 0; i < data.length(); i++) {
            Candle c = parseRow(item.token(), timeframe, data.getJSONArray(i));
            if (c != null
                    && (maxInclusiveTs == null || !c.getTs().isAfter(maxInclusiveTs))
                    && MarketUtils.isRegularMarketSession(c.getTs())) {
                candleRepo.upsert(c);
                candles.add(c);
            }
        }
        return candles;
    }

    private Candle parseRow(String token, String timeframe, JSONArray row) {
        try {
            LocalDateTime ts = OffsetDateTime.parse(row.getString(0), FMT).toLocalDateTime();
            return new Candle(
                token, timeframe, ts,
                row.getDouble(1),  // open
                row.getDouble(2),  // high
                row.getDouble(3),  // low
                row.getDouble(4),  // close
                row.getDouble(5)   // volume
            );
        } catch (Exception e) {
            log.warn("Candle row parse error: {}", e.getMessage());
            return null;
        }
    }

    private static LocalDateTime truncate(LocalDateTime dt, int barMinutes) {
        int min = dt.getMinute();
        int aligned = (min / barMinutes) * barMinutes;
        return dt.truncatedTo(ChronoUnit.HOURS).plusMinutes(aligned);
    }

    private static LocalDateTime truncateToMinute(LocalDateTime dt) {
        return dt.truncatedTo(ChronoUnit.MINUTES);
    }

    private static boolean isRateLimit(Exception e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("exceeding access rate");
    }

    private void enterRateLimitCooldown() {
        int cooldownMinutes = Math.max(1,
                AppConfig.getInt("trading.live.historical.refresh.cooldown.minutes", 15));
        rateLimitCooldownUntilMs = Math.max(rateLimitCooldownUntilMs,
                System.currentTimeMillis() + cooldownMinutes * 60_000L);
        log.warn("Historical API rate limit detected - pausing live historical refresh for {} minutes",
                cooldownMinutes);
    }
}
