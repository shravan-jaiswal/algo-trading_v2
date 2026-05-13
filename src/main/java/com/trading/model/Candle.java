package com.trading.model;

import java.time.LocalDateTime;

public class Candle {

    private final String        token;
    private final String        timeframe;
    private final LocalDateTime ts;
    private final double        open;
    private       double        high;
    private       double        low;
    private       double        close;
    private       double        volume;

    public Candle(String token, String timeframe, LocalDateTime ts,
                  double open, double high, double low, double close, double volume) {
        this.token     = token;
        this.timeframe = timeframe;
        this.ts        = ts;
        this.open      = open;
        this.high      = high;
        this.low       = low;
        this.close     = close;
        this.volume    = volume;
    }

    // Mutable setters — used by TickProcessor to update the live bar
    public void setHigh(double high)     { this.high   = high; }
    public void setLow(double low)       { this.low    = low; }
    public void setClose(double close)   { this.close  = close; }
    public void setVolume(double volume) { this.volume = volume; }

    public String        getToken()     { return token; }
    public String        getTimeframe() { return timeframe; }
    public LocalDateTime getTs()        { return ts; }
    public double        getOpen()      { return open; }
    public double        getHigh()      { return high; }
    public double        getLow()       { return low; }
    public double        getClose()     { return close; }
    public double        getVolume()    { return volume; }

    public double typicalPrice() { return (high + low + close) / 3.0; }
    public double range()        { return high - low; }
    public boolean isBullish()   { return close > open; }
    public boolean isBearish()   { return close < open; }

    @Override
    public String toString() {
        return "Candle[%s %s O=%.2f H=%.2f L=%.2f C=%.2f V=%.0f]"
                .formatted(token, ts, open, high, low, close, volume);
    }
}
