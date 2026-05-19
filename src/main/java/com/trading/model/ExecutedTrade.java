package com.trading.model;

import java.time.LocalDateTime;

public class ExecutedTrade {

    private int           id;
    private String        posKey;
    private String        token;
    private String        symbol;
    private String        strategyName;
    private double        entryPrice;
    private int           quantity;
    private String        side;        // BUY | SELL
    private String        status;      // OPEN | CLOSED
    private double        stopLoss;
    private double        takeProfit;
    private String        exchange;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private double        exitPrice;
    private double        pnl;
    private String        exitReason;

    public ExecutedTrade() {}

    // Getters and setters
    public int           getId()           { return id; }
    public void          setId(int id)     { this.id = id; }

    public String        getPosKey()       { return posKey; }
    public void          setPosKey(String v) { this.posKey = v; }

    public String        getToken()        { return token; }
    public void          setToken(String v) { this.token = v; }

    public String        getSymbol()       { return symbol; }
    public void          setSymbol(String v) { this.symbol = v; }

    public String        getStrategyName() { return strategyName; }
    public void          setStrategyName(String v) { this.strategyName = v; }

    public double        getEntryPrice()   { return entryPrice; }
    public void          setEntryPrice(double v) { this.entryPrice = v; }

    public int           getQuantity()     { return quantity; }
    public void          setQuantity(int v) { this.quantity = v; }

    public String        getSide()         { return side; }
    public void          setSide(String v) { this.side = v; }

    public String        getStatus()       { return status; }
    public void          setStatus(String v) { this.status = v; }

    public double        getStopLoss()     { return stopLoss; }
    public void          setStopLoss(double v) { this.stopLoss = v; }

    public double        getTakeProfit()   { return takeProfit; }
    public void          setTakeProfit(double v) { this.takeProfit = v; }

    public String        getExchange()     { return exchange; }
    public void          setExchange(String v) { this.exchange = v; }

    public LocalDateTime getEntryTime()    { return entryTime; }
    public void          setEntryTime(LocalDateTime v) { this.entryTime = v; }

    public LocalDateTime getExitTime()     { return exitTime; }
    public void          setExitTime(LocalDateTime v)  { this.exitTime = v; }

    public double        getExitPrice()    { return exitPrice; }
    public void          setExitPrice(double v) { this.exitPrice = v; }

    public double        getPnl()          { return pnl; }
    public void          setPnl(double v)  { this.pnl = v; }

    public String        getExitReason()   { return exitReason; }
    public void          setExitReason(String v) { this.exitReason = v; }

    public boolean isOpen() { return "OPEN".equalsIgnoreCase(status); }

    @Override
    public String toString() {
        return "ExecutedTrade[%s %s %s x%d @%.2f status=%s]"
                .formatted(strategyName, side, symbol, quantity, entryPrice, status);
    }
}
