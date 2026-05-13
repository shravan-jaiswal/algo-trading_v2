package com.trading.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaperBroker {

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);

    private record PaperPosition(String posKey, String symbol, int qty, double entryPrice, String side) {}

    private final Map<String, PaperPosition> positions = new ConcurrentHashMap<>();
    private double totalPnl = 0;

    public boolean openPosition(String posKey, String symbol, int qty, double price, String side) {
        if (positions.containsKey(posKey)) {
            log.warn("Paper: position already open | key={}", posKey);
            return false;
        }
        positions.put(posKey, new PaperPosition(posKey, symbol, qty, price, side));
        log.info("Paper OPEN | {} {} x{} @ Rs.{}", side, symbol, qty, String.format("%.2f", price));
        return true;
    }

    public boolean closePosition(String posKey, double price) {
        PaperPosition pos = positions.remove(posKey);
        if (pos == null) {
            log.warn("Paper: no position to close | key={}", posKey);
            return false;
        }
        double pnl = "BUY".equalsIgnoreCase(pos.side())
            ? (price - pos.entryPrice()) * pos.qty()
            : (pos.entryPrice() - price) * pos.qty();
        totalPnl += pnl;
        log.info("Paper CLOSE | {} {} x{} @ Rs.{} | P&L: Rs.{} | Total: Rs.{}",
                pos.side(), pos.symbol(), pos.qty(),
                String.format("%.2f", price),
                String.format("%+.2f", pnl),
                String.format("%+.2f", totalPnl));
        return true;
    }

    public boolean hasPosition(String posKey) { return positions.containsKey(posKey); }
    public double getTotalPnl()              { return totalPnl; }
    public int getOpenCount()                { return positions.size(); }

    public void printSummary() {
        log.info("── Paper Broker Summary ──");
        log.info("  Open positions : {}", positions.size());
        log.info("  Total P&L      : Rs.{}", String.format("%+.2f", totalPnl));
        positions.forEach((s, p) ->
            log.info("  {} | {} x{} @ Rs.{}", p.side(), s, p.qty(),
                    String.format("%.2f", p.entryPrice())));
    }
}
