package com.trading.data;

import com.trading.model.Candle;
import com.trading.model.Tick;
import com.trading.utils.MarketUtils;
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
 *
 * Two save paths:
 *   1. process() — triggered by a tick that crosses a bar boundary (normal path)
 *   2. flushCompleted() — triggered by wall-clock time; saves any candle whose
 *      time slot has fully elapsed without waiting for the next tick.
 *      Call this once per minute from a scheduler so the last candle of
 *      a slow-tick symbol is saved promptly.
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
        String        token    = tick.token();
        double        ltp      = tick.ltp();
        // Use exchange timestamp from tick (IST) — never LocalDateTime.now()
        LocalDateTime now      = tick.ts();

        LocalDateTime barStart = truncate(now, barMinutes);

        Candle live = liveCandles.get(token);

        if (live == null) {
            // First tick for this token
            live = newCandle(token, barStart, ltp, tick.volume());
            liveCandles.put(token, live);
            return null;
        }

        LocalDateTime liveBarStart = truncate(live.getTs(), barMinutes);

        if (!barStart.equals(liveBarStart)) {
            // Bar boundary crossed — persist the completed candle
            Candle closed = live;
            persistCandle(closed);
            getBuffer(token).add(closed);

            // Open new bar
            live = newCandle(token, barStart, ltp, tick.volume());
            liveCandles.put(token, live);
            return closed;
        }

        // Update live candle
        if (ltp > live.getHigh()) live.setHigh(ltp);
        if (ltp < live.getLow())  live.setLow(ltp);
        live.setClose(ltp);
        // LTP mode sends 0 volume — ignore zeros so volume isn't diluted
        if (tick.volume() > 0) live.setVolume(live.getVolume() + tick.volume());
        return null;
    }

    /**
     * Saves any candle whose time slot has fully elapsed based on wall clock.
     * Eliminates the race where a closed candle waits in memory until the first
     * tick of the next bar arrives (can be minutes on slow-tick symbols).
     * Call this once per minute from a scheduler.
     */
    public synchronized void flushCompleted() {
        LocalDateTime now         = LocalDateTime.now(MarketUtils.IST);
        LocalDateTime currentSlot = truncate(now, barMinutes);

        liveCandles.forEach((token, candle) -> {
            if (candle != null && truncate(candle.getTs(), barMinutes).isBefore(currentSlot)) {
                persistCandle(candle);
                getBuffer(token).add(candle);
                liveCandles.put(token, null);   // mark as flushed; cleared on next tick
            }
        });
        // Remove null sentinels left by flush
        liveCandles.entrySet().removeIf(e -> e.getValue() == null);
    }

    /**
     * Saves all open (partial) candles to DB — call on shutdown so the
     * in-progress bar is not lost on restart.
     */
    public synchronized void flush() {
        if (liveCandles.isEmpty()) return;
        log.info("Flushing {} open candles to DB on shutdown...", liveCandles.size());
        liveCandles.forEach((token, candle) -> {
            if (candle != null) persistCandle(candle);
        });
        liveCandles.clear();
        log.info("Flush complete.");
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

    private void persistCandle(Candle candle) {
        try {
            candleRepo.upsert(candle);
            log.debug("Candle saved | {} {} O:{} H:{} L:{} C:{} V:{}",
                    candle.getToken(), candle.getTs(),
                    candle.getOpen(), candle.getHigh(),
                    candle.getLow(),  candle.getClose(), candle.getVolume());
        } catch (Exception e) {
            log.error("Failed to save candle | token:{} time:{} — {}",
                    candle.getToken(), candle.getTs(), e.getMessage());
        }
    }

    private Candle newCandle(String token, LocalDateTime barStart, double price, double volume) {
        return switch (barMinutes) {
            case 1  -> new Candle(token, "ONE_MINUTE",     barStart, price, price, price, price, volume);
            case 3  -> new Candle(token, "THREE_MINUTE",   barStart, price, price, price, price, volume);
            case 5  -> new Candle(token, "FIVE_MINUTE",    barStart, price, price, price, price, volume);
            case 15 -> new Candle(token, "FIFTEEN_MINUTE", barStart, price, price, price, price, volume);
            default -> new Candle(token, barMinutes + "_MINUTE", barStart, price, price, price, price, volume);
        };
    }

    private static LocalDateTime truncate(LocalDateTime dt, int barMinutes) {
        int min     = dt.getMinute();
        int aligned = (min / barMinutes) * barMinutes;
        return dt.truncatedTo(ChronoUnit.HOURS).plusMinutes(aligned);
    }
}
