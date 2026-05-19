package com.trading.data;

import com.angelbroking.smartapi.SmartConnect;
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
import java.util.ArrayList;
import java.util.List;

public class HistoricalDataFetcher {

    private static final Logger          log   = LoggerFactory.getLogger(HistoricalDataFetcher.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private SmartConnect    smartConnect;
    private final CandleRepository candleRepo;

    public HistoricalDataFetcher(SmartConnect smartConnect, CandleRepository candleRepo) {
        this.smartConnect = smartConnect;
        this.candleRepo   = candleRepo;
    }

    public void setSmartConnect(SmartConnect sc) { this.smartConnect = sc; }
    public boolean isConnected() { return smartConnect != null; }

    /**
     * Backfills historical candles for a watchlist item.
     * Fetches {@code days} of history and stores in DB + returns the list.
     */
    public List<Candle> backfill(WatchlistItem item, String timeframe, int days) {
        List<Candle> candles = new ArrayList<>();
        try {
            LocalDate from = LocalDate.now().minusDays(days);
            LocalDate to   = LocalDate.now();

            JSONObject params = new JSONObject();
            params.put("exchange",    item.exchange());
            params.put("symboltoken", item.token());
            params.put("interval",    timeframe);
            params.put("fromdate",    from + " 09:15");
            params.put("todate",      to   + " 15:30");

            JSONArray data = smartConnect.candleData(params);
            if (data == null) {
                log.warn("No candle data returned for {}", item.symbol());
                return candles;
            }
            for (int i = 0; i < data.length(); i++) {
                JSONArray row = data.getJSONArray(i);
                Candle c = parseRow(item.token(), timeframe, row);
                if (c != null && MarketUtils.isRegularMarketSession(c.getTs())) {
                    candles.add(c);
                    candleRepo.upsert(c);
                }
            }

            log.info("Backfilled {} candles for {} ({})", candles.size(), item.symbol(), timeframe);

        } catch (Exception e) {
            log.error("Backfill failed for {}: {}", item.symbol(), e.getMessage());
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
}
