package com.trading.backtest;

import com.trading.config.AppConfig;
import com.trading.model.Candle;
import com.trading.risk.RiskConfig;
import com.trading.risk.RiskManager;
import com.trading.strategy.HoldingType;
import com.trading.strategy.Strategy;
import com.trading.strategy.Strategy.Signal;
import com.trading.strategy.TradeType;
import com.trading.utils.MarketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple event-driven backtester.
 * Replays a candle list bar-by-bar, evaluates a strategy, and tracks P&L.
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private static final String BACKTEST_POSITION_KEY = "BACKTEST";

    public record Trade(java.time.LocalDateTime entryTime, java.time.LocalDateTime exitTime,
                        double entry, double exit, int qty, double pnl, String side,
                        String exitReason) {}

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
            String   holdingType,
            List<Trade> trades
    ) {
        public long longTrades() {
            return trades.stream().filter(t -> Signal.LONG.name().equals(t.side())).count();
        }

        public long shortTrades() {
            return trades.stream().filter(t -> Signal.SHORT.name().equals(t.side())).count();
        }

        public void print() {
            log.info("-- Backtest Results ------------------");
            log.info("  Holding Type  : {}", holdingType);
            log.info("  Trades        : {}", totalTrades);
            log.info("  Long Trades   : {}", longTrades());
            log.info("  Short Trades  : {}", shortTrades());
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
                    log.info("    {} | Entry: {} @ Rs.{} | Exit: {} @ Rs.{} | Qty: {} | P&L: Rs.{} | reason={}",
                            t.side(),
                            t.entryTime().toString().replace("T", " "),
                            String.format("%.2f", t.entry()),
                            t.exitTime().toString().replace("T", " "),
                            String.format("%.2f", t.exit()),
                            t.qty(),
                            String.format("%+.2f", t.pnl()),
                            t.exitReason());
                }
            }
            log.info("--------------------------------------");
        }
    }

    private final Strategy    strategy;
    private final RiskManager riskManager;
    private final boolean     intradayOnly;
    private final HoldingType holdingType;
    private final TradeType   tradeType;
    private final LocalTime   squareOffTime;
    private final double      fixedCostPerTrade;
    private final double      roundTripCostBps;

    public BacktestEngine(Strategy strategy, RiskConfig riskConfig) {
        this(strategy, riskConfig, HoldingType.fromConfig(strategy));
    }

    public BacktestEngine(Strategy strategy, RiskConfig riskConfig, boolean intradayOnly) {
        this(strategy, riskConfig, intradayOnly ? HoldingType.INTRADAY : HoldingType.DELIVERY);
    }

    public BacktestEngine(Strategy strategy, RiskConfig riskConfig, HoldingType holdingType) {
        this.strategy     = strategy;
        this.riskManager  = new RiskManager(riskConfig);
        this.holdingType  = holdingType == null ? HoldingType.INTRADAY : holdingType;
        this.intradayOnly = this.holdingType.isIntraday();
        this.tradeType    = TradeType.fromConfig(strategy);
        this.squareOffTime = LocalTime.parse(AppConfig.get("market.squareoff", "15:15"));
        this.fixedCostPerTrade = Math.max(0,
                AppConfig.getDouble("backtest.fixed.cost.per.trade", 0));
        this.roundTripCostBps = Math.max(0,
                AppConfig.getDouble("backtest.round.trip.cost.bps", 0));
    }

    public BacktestResult run(List<Candle> candles) {
        candles = candles == null ? List.of() : candles.stream()
                .filter(c -> MarketUtils.isRegularMarketSession(c.getTs()))
                .toList();
        List<Trade> trades    = new ArrayList<>();
        int minBars           = strategy.getMinCandles();

        double entryPrice                    = 0;
        int    qty                           = 0;
        Signal openSide                      = Signal.NONE;
        double stopLoss                      = 0;
        double takeProfit                    = 0;
        java.time.LocalDateTime entryTime    = null;
        double equity         = riskManager.getConfig().capital();
        double peakEquity     = equity;
        double maxDrawdown    = 0;

        for (int i = minBars; i < candles.size(); i++) {
            List<Candle> window = candles.subList(0, i + 1);
            Candle bar          = candles.get(i);
            double close        = bar.getClose();
            java.time.LocalDate barDay = bar.getTs().toLocalDate();

            // ── EOD squareoff: close position at last candle of the day ─
            if (intradayOnly) {
                boolean squareOffBar = !bar.getTs().toLocalTime().isBefore(squareOffTime);
                boolean lastBarOfDay = (i == candles.size() - 1)
                        || !candles.get(i + 1).getTs().toLocalDate().equals(barDay);
                if (openSide != Signal.NONE && (squareOffBar || lastBarOfDay)) {
                    double pnl = netPnl(openSide, entryPrice, close, qty);
                    trades.add(new Trade(entryTime, bar.getTs(), entryPrice, close, qty, pnl,
                            openSide.name(), squareOffBar ? "INTRADAY_EXIT" : "EOD_FALLBACK"));
                    equity   += pnl;
                    riskManager.clearTSL(positionKey(openSide));
                    openSide  = Signal.NONE;
                    if (equity > peakEquity) peakEquity = equity;
                    double dd = peakEquity - equity;
                    if (dd > maxDrawdown)   maxDrawdown = dd;
                    continue;
                }
                if (squareOffBar) continue;
            }

            // ── Check exit if in a position ──────────────────────────
            if (openSide != Signal.NONE) {
                boolean exitNow = false;
                String exitReason = "";
                Signal sig = strategy.evaluate(window);
                String posKey = positionKey(openSide);

                if (openSide == Signal.LONG && sig.isLongExit()) {
                    exitNow = true;
                    exitReason = "SIGNAL";
                } else if (openSide == Signal.SHORT && sig.isShortExit()) {
                    exitNow = true;
                    exitReason = "SIGNAL";
                } else if (openSide == Signal.LONG) {
                    if (close <= stopLoss)  { exitNow = true; exitReason = "SL"; }
                    if (!exitNow) {
                        riskManager.updateTSL(posKey, close);
                        if (riskManager.isTSLHit(posKey, close)) {
                            exitNow = true;
                            exitReason = "TSL";
                        }
                    }
                    if (!exitNow && takeProfit > 0 && close >= takeProfit) {
                        exitNow = true;
                        exitReason = "TP";
                    }
                } else {
                    if (close >= stopLoss)  { exitNow = true; exitReason = "SL"; }
                    if (!exitNow) {
                        riskManager.updateTSLShort(posKey, close);
                        if (riskManager.isTSLHitShort(posKey, close)) {
                            exitNow = true;
                            exitReason = "TSL";
                        }
                    }
                    if (!exitNow && takeProfit > 0 && close <= takeProfit) {
                        exitNow = true;
                        exitReason = "TP";
                    }
                }

                if (exitNow) {
                    double pnl = netPnl(openSide, entryPrice, close, qty);
                    trades.add(new Trade(entryTime, bar.getTs(), entryPrice, close, qty, pnl,
                            openSide.name(), exitReason));
                    equity    += pnl;
                    riskManager.clearTSL(posKey);
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
                if (!sig.isEntry()) continue;
                if (!tradeType.allows(sig)) continue;

                double sl = strategy.suggestStopLoss(window, sig);
                if (sl <= 0) {
                    double atrEst = (bar.getHigh() - bar.getLow());
                    sl = sig.isLongEntry() ? close - atrEst * 1.5 : close + atrEst * 1.5;
                }

                qty = riskManager.positionSize(close, sl);
                if (qty <= 0) continue;

                entryPrice = close;
                entryTime  = bar.getTs();
                stopLoss   = sl;
                takeProfit = riskManager.calculateTakeProfit(close, sl, sig);
                openSide   = sig;
                if (openSide == Signal.LONG) {
                    riskManager.initTSL(positionKey(openSide), close);
                } else if (openSide == Signal.SHORT) {
                    riskManager.initTSLShort(positionKey(openSide), close);
                }
            }
        }

        // ── Force-close any open position at last bar ─────────────────
        if (openSide != Signal.NONE && !candles.isEmpty()) {
            Candle lastBar   = candles.get(candles.size() - 1);
            double lastClose = lastBar.getClose();
            double pnl = netPnl(openSide, entryPrice, lastClose, qty);
            trades.add(new Trade(entryTime, lastBar.getTs(), entryPrice, lastClose, qty, pnl,
                    openSide.name(), "FINAL_BAR"));
            riskManager.clearTSL(positionKey(openSide));
            equity += pnl;
        }

        return computeStats(trades, maxDrawdown);
    }

    private BacktestResult computeStats(List<Trade> trades, double maxDrawdown) {
        if (trades.isEmpty()) {
            return new BacktestResult(0, 0, 0, 0, 0, 0, 0, 0, 0,
                    holdingType.name(), List.of());
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
                avgWin, avgLoss, pf, holdingType.name(), trades
        );
    }

    private String positionKey(Signal side) {
        return BACKTEST_POSITION_KEY + "|" + strategy.getName() + "|" + side.name();
    }

    private double netPnl(Signal side, double entry, double exit, int qty) {
        double gross = side == Signal.LONG
                ? (exit - entry) * qty
                : (entry - exit) * qty;
        double turnoverCost = (entry + exit) * qty * roundTripCostBps / 10_000.0;
        return gross - fixedCostPerTrade - turnoverCost;
    }
}
