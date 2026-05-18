package com.trading.backtest;

import com.trading.model.Candle;
import com.trading.risk.RiskConfig;
import com.trading.risk.RiskManager;
import com.trading.strategy.Strategy;
import com.trading.strategy.Strategy.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple event-driven backtester.
 * Replays a candle list bar-by-bar, evaluates a strategy, and tracks P&L.
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    public record Trade(java.time.LocalDateTime entryTime, java.time.LocalDateTime exitTime,
                        double entry, double exit, int qty, double pnl, String side) {}

    public record BacktestResult(
            int      totalTrades,
            int      winners,
            int      losers,
            double   totalPnl,
            double   maxDrawdown,
            double   winRate,
            double   avgWin,
            double   avgLoss,
            double   profitFactor,
            List<Trade> trades
    ) {
        public void print() {
            log.info("-- Backtest Results ------------------");
            log.info("  Trades        : {}", totalTrades);
            log.info("  Winners       : {} ({}%)", winners, String.format("%.1f", winRate * 100));
            log.info("  Losers        : {}", losers);
            log.info("  Total P&L     : Rs.{}", String.format("%+.2f", totalPnl));
            log.info("  Max Drawdown  : Rs.{}", String.format("%.2f", maxDrawdown));
            log.info("  Avg Win       : Rs.{}", String.format("%.2f", avgWin));
            log.info("  Avg Loss      : Rs.{}", String.format("%.2f", avgLoss));
            log.info("  Profit Factor : {}", profitFactor == Double.MAX_VALUE ? "N/A (no losses)" : String.format("%.2f", profitFactor));
            if (!trades.isEmpty()) {
                log.info("  --- Trades ---");
                for (Trade t : trades) {
                    log.info("    {} | Entry: {} @ Rs.{} | Exit: {} @ Rs.{} | Qty: {} | P&L: Rs.{}",
                            t.side(),
                            t.entryTime().toString().replace("T", " "),
                            String.format("%.2f", t.entry()),
                            t.exitTime().toString().replace("T", " "),
                            String.format("%.2f", t.exit()),
                            t.qty(),
                            String.format("%+.2f", t.pnl()));
                }
            }
            log.info("--------------------------------------");
        }
    }

    private final Strategy    strategy;
    private final RiskManager riskManager;
    private final boolean     intradayOnly;

    public BacktestEngine(Strategy strategy, RiskConfig riskConfig) {
        this(strategy, riskConfig, false);
    }

    public BacktestEngine(Strategy strategy, RiskConfig riskConfig, boolean intradayOnly) {
        this.strategy     = strategy;
        this.riskManager  = new RiskManager(riskConfig);
        this.intradayOnly = intradayOnly;
    }

    public BacktestResult run(List<Candle> candles) {
        List<Trade> trades    = new ArrayList<>();
        int minBars           = strategy.getMinCandles();

        double entryPrice                    = 0;
        int    qty                           = 0;
        Signal openSide                      = Signal.NONE;
        double stopLoss                      = 0;
        double takeProfit                    = 0;
        java.time.LocalDateTime entryTime    = null;
        double peakEquity     = 0;
        double maxDrawdown    = 0;
        double equity         = riskManager.getConfig().capital();

        for (int i = minBars; i < candles.size(); i++) {
            List<Candle> window = candles.subList(0, i + 1);
            Candle bar          = candles.get(i);
            double close        = bar.getClose();
            java.time.LocalDate barDay = bar.getTs().toLocalDate();

            // ── EOD squareoff: close position at last candle of the day ─
            if (intradayOnly && openSide != Signal.NONE) {
                boolean lastBarOfDay = (i == candles.size() - 1)
                        || !candles.get(i + 1).getTs().toLocalDate().equals(barDay);
                if (lastBarOfDay) {
                    double pnl = openSide == Signal.BUY
                            ? (close - entryPrice) * qty
                            : (entryPrice - close) * qty;
                    trades.add(new Trade(entryTime, bar.getTs(), entryPrice, close, qty, pnl, openSide.name()));
                    equity   += pnl;
                    openSide  = Signal.NONE;
                    if (equity > peakEquity) peakEquity = equity;
                    double dd = peakEquity - equity;
                    if (dd > maxDrawdown)   maxDrawdown = dd;
                    continue;
                }
            }

            // ── Check exit if in a position ──────────────────────────
            if (openSide != Signal.NONE) {
                boolean exitNow = false;

                if (openSide == Signal.BUY) {
                    if (close <= stopLoss)  exitNow = true;  // SL
                    if (takeProfit > 0 && close >= takeProfit) exitNow = true;  // TP
                } else {
                    if (close >= stopLoss)  exitNow = true;
                    if (takeProfit > 0 && close <= takeProfit) exitNow = true;
                }

                if (exitNow) {
                    double pnl = openSide == Signal.BUY
                            ? (close - entryPrice) * qty
                            : (entryPrice - close) * qty;
                    trades.add(new Trade(entryTime, bar.getTs(), entryPrice, close, qty, pnl, openSide.name()));
                    equity    += pnl;
                    openSide   = Signal.NONE;

                    if (equity > peakEquity) peakEquity = equity;
                    double dd = peakEquity - equity;
                    if (dd > maxDrawdown)   maxDrawdown = dd;
                    continue;
                }
            }

            // ── Entry ────────────────────────────────────────────────
            if (openSide == Signal.NONE) {
                Signal sig = strategy.evaluate(window);
                if (sig == Signal.NONE) continue;

                double sl = strategy.suggestStopLoss(window, sig);
                if (sl <= 0) {
                    double atrEst = (bar.getHigh() - bar.getLow());
                    sl = sig == Signal.BUY ? close - atrEst * 1.5 : close + atrEst * 1.5;
                }

                qty = riskManager.positionSize(close, sl);
                if (qty <= 0) continue;

                entryPrice = close;
                entryTime  = bar.getTs();
                stopLoss   = sl;
                takeProfit = riskManager.calculateTakeProfit(close, sl, sig);
                openSide   = sig;
            }
        }

        // ── Force-close any open position at last bar ─────────────────
        if (openSide != Signal.NONE && !candles.isEmpty()) {
            Candle lastBar   = candles.get(candles.size() - 1);
            double lastClose = lastBar.getClose();
            double pnl = openSide == Signal.BUY
                    ? (lastClose - entryPrice) * qty
                    : (entryPrice - lastClose) * qty;
            trades.add(new Trade(entryTime, lastBar.getTs(), entryPrice, lastClose, qty, pnl, openSide.name()));
            equity += pnl;
        }

        return computeStats(trades, maxDrawdown);
    }

    private BacktestResult computeStats(List<Trade> trades, double maxDrawdown) {
        if (trades.isEmpty()) {
            return new BacktestResult(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        double totalPnl  = trades.stream().mapToDouble(Trade::pnl).sum();
        int    winners   = (int) trades.stream().filter(t -> t.pnl() > 0).count();
        int    losers    = trades.size() - winners;
        double avgWin    = trades.stream().filter(t -> t.pnl() > 0)
                .mapToDouble(Trade::pnl).average().orElse(0);
        double avgLoss   = trades.stream().filter(t -> t.pnl() < 0)
                .mapToDouble(t -> Math.abs(t.pnl())).average().orElse(0);
        double grossWin  = trades.stream().filter(t -> t.pnl() > 0).mapToDouble(Trade::pnl).sum();
        double grossLoss = trades.stream().filter(t -> t.pnl() < 0).mapToDouble(t -> Math.abs(t.pnl())).sum();
        double pf        = grossLoss > 0 ? grossWin / grossLoss : Double.MAX_VALUE;
        double winRate   = trades.isEmpty() ? 0 : (double) winners / trades.size();

        return new BacktestResult(
                trades.size(), winners, losers,
                totalPnl, maxDrawdown, winRate,
                avgWin, avgLoss, pf, trades
        );
    }
}
