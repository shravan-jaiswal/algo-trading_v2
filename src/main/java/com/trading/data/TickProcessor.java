package com.trading.data;

import com.trading.model.Candle;
import com.trading.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts real-time LTP ticks into OHLCV candles.
 * Maintains a live candle per token; closes it on the next bar boundary.
 */
public class TickProcessor {

    private static final Logger log = LoggerFactory.getLogger(TickProcessor.class);

    private final int              barMinutes;
    private final CandleRepository candleRepo;

    // token → live (unfinished) candle
    private final Map<String, Candle> liveCandles = new ConcurrentHashMap<>();

    // token → completed candle list (in-memory buffer)
    private final Map<String, List<Candle>> candleBuffer = new ConcurrentHashMap<>();

    public TickProcessor(int barMinutes, CandleRepository candleRepo) {
        this.barMinutes = barMinutes;
        this.candleRepo = candleRepo;
    }

    /** Call this for every incoming tick. Returns the completed candle if the bar just closed. */
    public synchronized Candle process(Tick tick) {
        String        token = tick.token();
        double        ltp   = tick.ltp();
        LocalDateTime now   = tick.ts();

        LocalDateTime barStart = truncate(now, barMinutes);

        Candle live = liveCandles.get(token);

        if (live == null) {
            // First tick for this token
            live = newCandle(token, barStart, ltp);
            liveCandles.put(token, live);
            return null;
        }

        LocalDateTime liveBarStart = truncate(live.getTs(), barMinutes);

        if (!barStart.equals(liveBarStart)) {
            // Bar boundary crossed — persist the completed candle
            Candle closed = live;
            candleRepo.upsert(closed);
            getBuffer(token).add(closed);

            // Open new bar
            live = newCandle(token, barStart, ltp);
            liveCandles.put(token, live);
            return closed;
        }

        // Update live candle
        if (ltp > live.getHigh()) live.setHigh(ltp);
        if (ltp < live.getLow())  live.setLow(ltp);
        live.setClose(ltp);
        live.setVolume(live.getVolume() + tick.volume());
        return null;
    }

    /** Returns the candle list for a token (completed + live bar appended). */
    public List<Candle> getCandles(String token) {
        List<Candle> buf  = getBuffer(token);
        Candle live       = liveCandles.get(token);
        List<Candle> all  = new ArrayList<>(buf);
        if (live != null) all.add(live);
        return all;
    }

    /** Seed the buffer from historical data (called on startup). */
    public void seed(String token, List<Candle> historical) {
        List<Candle> buf = getBuffer(token);
        buf.clear();
        buf.addAll(historical);
        log.info("TickProcessor seeded | {} | {} candles", token, historical.size());
    }

    /** Trim buffer to keep only the last {@code maxSize} candles. */
    public void trim(String token, int maxSize) {
        List<Candle> buf = getBuffer(token);
        if (buf.size() > maxSize) {
            buf.subList(0, buf.size() - maxSize).clear();
        }
    }

    private List<Candle> getBuffer(String token) {
        return candleBuffer.computeIfAbsent(token, k -> new ArrayList<>());
    }

    private Candle newCandle(String token, LocalDateTime barStart, double price) {
        String tf = barMinutes + "_MINUTE";
        return switch (barMinutes) {
            case 1  -> new Candle(token, "ONE_MINUTE",     barStart, price, price, price, price, 0);
            case 3  -> new Candle(token, "THREE_MINUTE",   barStart, price, price, price, price, 0);
            case 5  -> new Candle(token, "FIVE_MINUTE",    barStart, price, price, price, price, 0);
            case 15 -> new Candle(token, "FIFTEEN_MINUTE", barStart, price, price, price, price, 0);
            default -> new Candle(token, tf,               barStart, price, price, price, price, 0);
        };
    }

    private static LocalDateTime truncate(LocalDateTime dt, int barMinutes) {
        int min     = dt.getMinute();
        int aligned = (min / barMinutes) * barMinutes;
        return dt.truncatedTo(ChronoUnit.HOURS).plusMinutes(aligned);
    }
}
