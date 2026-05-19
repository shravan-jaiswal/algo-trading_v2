package com.trading.utils;

import com.trading.config.AppConfig;
import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.InstrumentType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class MarketUtils {

    public static final ZoneId    IST          = ZoneId.of("Asia/Kolkata");
    public static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    public static final LocalTime SQUARE_OFF   = LocalTime.of(15, 15);

    // NSE/NFO standard tick size for all instrument types
    public static final double TICK_EQUITY  = 0.05;
    public static final double TICK_FUTURES = 0.05;
    public static final double TICK_OPTIONS = 0.05;

    private MarketUtils() {}

    public static boolean isMarketOpen() {
        return isRegularMarketSession(LocalDateTime.now(IST));
    }

    public static boolean isRegularMarketSession(LocalDateTime ts) {
        if (ts == null) return false;
        DayOfWeek day = ts.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime t = ts.toLocalTime();
        return !t.isBefore(marketOpenTime()) && t.isBefore(marketCloseTime());
    }

    public static boolean isSquareOffTime() {
        LocalTime t = ZonedDateTime.now(IST).toLocalTime();
        return !t.isBefore(squareOffTime());
    }

    public static LocalTime marketOpenTime() {
        return configuredTime("market.open", MARKET_OPEN);
    }

    public static LocalTime marketCloseTime() {
        return configuredTime("market.close", MARKET_CLOSE);
    }

    public static LocalTime squareOffTime() {
        return configuredTime("market.squareoff", SQUARE_OFF);
    }

    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    /** Rounds price to the nearest valid tick for the given instrument type. */
    public static double roundToTick(double price, InstrumentType type) {
        double tick = switch (type) {
            case OPTION_BUY, OPTION_WRITE -> TICK_OPTIONS;
            case FUTURES                  -> TICK_FUTURES;
            default                       -> TICK_EQUITY;
        };
        return roundToTick(price, tick);
    }

    /** Rounds price to the nearest multiple of the given tick size. */
    public static double roundToTick(double price, double tickSize) {
        return Math.round(price / tickSize) * tickSize;
    }

    /** Convenience overload using InstrumentConfig. */
    public static double roundToTick(double price, InstrumentConfig cfg) {
        return roundToTick(price, cfg.type());
    }

    /** Legacy overload — defaults to equity tick size. */
    public static double roundToTick(double price) {
        return roundToTick(price, TICK_EQUITY);
    }

    public static double buyLimitPrice(double ltp, double slippagePct, InstrumentType type) {
        return roundToTick(ltp * (1 + slippagePct), type);
    }

    public static double sellLimitPrice(double ltp, double slippagePct, InstrumentType type) {
        return roundToTick(ltp * (1 - slippagePct), type);
    }

    /** Legacy overloads used by OrderManager for equity orders. */
    public static double buyLimitPrice(double ltp, double slippagePct) {
        return roundToTick(ltp * (1 + slippagePct), TICK_EQUITY);
    }

    public static double sellLimitPrice(double ltp, double slippagePct) {
        return roundToTick(ltp * (1 - slippagePct), TICK_EQUITY);
    }

    private static LocalTime configuredTime(String key, LocalTime fallback) {
        try {
            return LocalTime.parse(AppConfig.get(key, fallback.toString()));
        } catch (Exception e) {
            return fallback;
        }
    }
}
