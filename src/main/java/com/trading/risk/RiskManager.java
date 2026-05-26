package com.trading.risk;

import com.trading.strategy.Strategy.Signal;
import com.trading.utils.Shared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    private final RiskConfig cfg;

    // ── Runtime state (lock-free) ─────────────────────────────────────
    private final AtomicReference<Double>  dailyLoss       = new AtomicReference<>(0.0);
    private final AtomicReference<Double>  dailyProfit     = new AtomicReference<>(0.0);
    private final AtomicReference<Double>  deployedCapital = new AtomicReference<>(0.0);
    private final AtomicInteger            tradesToday     = new AtomicInteger(0);
    private final AtomicInteger            openPositions   = new AtomicInteger(0);
    private final AtomicReference<Boolean> haltTrading     = new AtomicReference<>(false);

    // ── Per-symbol P&L and capital tracking ──────────────────────────
    private final Map<String, Double> symbolPnL      = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolDeployed = new ConcurrentHashMap<>();

    // ── TSL state: AtomicReference<TslState> per token ───────────────
    private final Map<String, AtomicReference<TslState>> tslState = new ConcurrentHashMap<>();

    public RiskManager(RiskConfig cfg) {
        this.cfg = cfg;
    }

    public void logInit() {
        log.info("RiskManager initialized");
        log.info("  Capital         : Rs.{}", Shared.fmt(cfg.capital()));
        log.info("  Risk/Trade      : {}", Shared.fmtPct(cfg.maxRiskPerTrade()));
        log.info("  Max Daily Loss  : {}", Shared.fmtPct(cfg.maxDailyLoss()));
        log.info("  Max Daily Profit: {}", Shared.fmtPct(cfg.maxDailyProfit()));
        log.info("  R:R Ratio       : 1:{}", cfg.riskRewardRatio());
        log.info("  TSL Trail       : {}", Shared.fmtPct(cfg.tslTrailPct()));
        log.info("  Max Trades/Day  : {}", cfg.maxTradesPerDay());
        log.info("  Max Open Pos    : {}", cfg.maxOpenPositions());
        log.info("  Max Pos Size    : {}", Shared.fmtPct(cfg.maxPositionSize()));
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. GATE — can we open a new trade?
    // ─────────────────────────────────────────────────────────────────

    public boolean canTrade() {
        return checkGuards(true);
    }

    public boolean canOpenTrade(double entryPrice, int qty) {
        double required = entryPrice * qty;
        return canOpenTradeForCapital(required, "notional");
    }

    public boolean canOpenTradeForCapital(double requiredCapital, String source) {
        if (!canTrade()) return false;
        if (requiredCapital <= 0) {
            log.warn("Trade blocked by capital | invalid required capital:{} source:{}",
                    Shared.fmt(requiredCapital), source);
            return false;
        }
        double available = getAvailableCapital();
        if (requiredCapital > available) {
            log.warn("Trade blocked by capital | required:Rs.{} source:{} available:Rs.{} deployed:Rs.{} total:Rs.{}",
                    Shared.fmt(requiredCapital), source, Shared.fmt(available),
                    Shared.fmt(deployedCapital.get()), Shared.fmt(cfg.capital()));
            return false;
        }
        return true;
    }

    /** Bypass position-count limit for paired hedge legs (e.g. MICS spread). */
    public boolean canTradeIgnoringPositionLimit() {
        return checkGuards(false);
    }

    private boolean checkGuards(boolean enforcePositionLimit) {
        if (haltTrading.get()) {
            log.warn("Trading HALTED.");
            return false;
        }
        if (isDailyLossBreached()) {
            haltTrading.set(true);
            log.warn("Daily loss limit breached | loss:Rs.{} / limit:Rs.{}",
                    Shared.fmt(dailyLoss.get()), Shared.fmt(cfg.capital() * cfg.maxDailyLoss()));
            return false;
        }
        if (isDailyProfitReached()) {
            haltTrading.set(true);
            log.info("Daily profit target reached | profit:Rs.{} / target:Rs.{}",
                    Shared.fmt(dailyProfit.get()), Shared.fmt(cfg.capital() * cfg.maxDailyProfit()));
            return false;
        }
        if (tradesToday.get() >= cfg.maxTradesPerDay()) {
            log.warn("Max trades/day reached: {}", cfg.maxTradesPerDay());
            return false;
        }
        if (enforcePositionLimit && openPositions.get() >= cfg.maxOpenPositions()) {
            log.warn("Max open positions reached: {}", cfg.maxOpenPositions());
            return false;
        }
        if (getAvailableCapital() <= 0) {
            log.warn("No available capital | deployed:Rs.{} / total:Rs.{}",
                    Shared.fmt(deployedCapital.get()), Shared.fmt(cfg.capital()));
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. POSITION SIZING
    // ─────────────────────────────────────────────────────────────────

    public int positionSize(double entryPrice, double stopLossPrice) {
        if (entryPrice <= 0 || stopLossPrice <= 0) {
            log.error("Invalid prices | entry:{} sl:{}", entryPrice, stopLossPrice);
            return 0;
        }
        double available   = getAvailableCapital();
        if (available <= 0) return 0;

        double riskAmount  = cfg.capital() * cfg.maxRiskPerTrade();
        double riskPerUnit = Math.abs(entryPrice - stopLossPrice);
        if (riskPerUnit == 0) {
            log.error("Entry and stop loss cannot be equal.");
            return 0;
        }

        int qty         = (int) Math.floor(riskAmount / riskPerUnit);
        int maxByPct    = (int) Math.floor((cfg.capital() * cfg.maxPositionSize()) / entryPrice);
        int maxByAvail  = (int) Math.floor(available / entryPrice);

        qty = Math.min(qty, Math.min(maxByPct, maxByAvail));
        if (qty <= 0) {
            log.warn("Position size 0 | entry:{} sl:{} available:Rs.{}",
                    entryPrice, stopLossPrice, Shared.fmt(available));
        }
        return Math.max(0, qty);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. STOP LOSS & TAKE PROFIT
    // ─────────────────────────────────────────────────────────────────

    public double calculateStopLoss(double entryPrice, double atr,
                                    double multiplier, Signal signal) {
        return signal.isLongEntry()
            ? entryPrice - atr * multiplier
            : entryPrice + atr * multiplier;
    }

    public double calculateTakeProfit(double entryPrice, double stopLoss, Signal signal) {
        if (cfg.riskRewardRatio() <= 0) return 0;
        double risk   = Math.abs(entryPrice - stopLoss);
        double reward = risk * cfg.riskRewardRatio();
        return signal.isLongEntry()
            ? entryPrice + reward
            : entryPrice - reward;
    }

    public boolean isTakeProfitHit(String token, double currentPrice, double takeProfit) {
        if (takeProfit <= 0) return false;
        boolean hit = currentPrice >= takeProfit;
        if (hit) log.info("TP HIT | {} | price:Rs.{} | tp:Rs.{}", token,
                Shared.fmt(currentPrice), Shared.fmt(takeProfit));
        return hit;
    }

    public boolean isTakeProfitHitShort(String token, double currentPrice, double takeProfit) {
        if (takeProfit <= 0) return false;
        boolean hit = currentPrice <= takeProfit;
        if (hit) log.info("TP SHORT HIT | {} | price:Rs.{} | tp:Rs.{}", token,
                Shared.fmt(currentPrice), Shared.fmt(takeProfit));
        return hit;
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. TRAILING STOP LOSS
    // ─────────────────────────────────────────────────────────────────

    public double initTSL(String token, double entryPrice) {
        return initTSL(token, entryPrice, cfg.tslTrailPct());
    }

    public double initTSL(String token, double entryPrice, double trailPct) {
        if (trailPct <= 0) return 0;
        TslState.Long state = TslState.initLong(entryPrice, trailPct);
        tslState.put(token, new AtomicReference<>(state));
        log.info("TSL init | {} | entry:Rs.{} | trail:{}% | stop:Rs.{}",
                token, Shared.fmt(entryPrice), Shared.fmtPct(trailPct), Shared.fmt(state.stop()));
        return state.stop();
    }

    public double initTSLShort(String token, double entryPrice) {
        return initTSLShort(token, entryPrice, cfg.tslTrailPct());
    }

    public double initTSLShort(String token, double entryPrice, double trailPct) {
        if (trailPct <= 0) return 0;
        TslState.Short state = TslState.initShort(entryPrice, trailPct);
        tslState.put(token, new AtomicReference<>(state));
        log.info("TSL SHORT init | {} | entry:Rs.{} | trail:{}% | stop:Rs.{}",
                token, Shared.fmt(entryPrice), Shared.fmtPct(trailPct), Shared.fmt(state.stop()));
        return state.stop();
    }

    public double updateTSL(String token, double currentPrice) {
        return updateTSL(token, currentPrice, cfg.tslTrailPct());
    }

    public double updateTSL(String token, double currentPrice, double trailPct) {
        if (trailPct <= 0) return 0;
        AtomicReference<TslState> ref = tslState.get(token);
        if (ref == null) return 0;
        TslState updated = ref.updateAndGet(s -> switch (s) {
            case TslState.Long  l  -> l.update(currentPrice, trailPct);
            case TslState.Short sh -> {
                log.warn("updateTSL called on SHORT position for token {}", token);
                yield sh;
            }
        });
        return updated.stop();
    }

    public double updateTSLShort(String token, double currentPrice) {
        return updateTSLShort(token, currentPrice, cfg.tslTrailPct());
    }

    public double updateTSLShort(String token, double currentPrice, double trailPct) {
        if (trailPct <= 0) return 0;
        AtomicReference<TslState> ref = tslState.get(token);
        if (ref == null) return 0;
        TslState updated = ref.updateAndGet(s -> switch (s) {
            case TslState.Short sh -> sh.update(currentPrice, trailPct);
            case TslState.Long  l  -> {
                log.warn("updateTSLShort called on LONG position for token {}", token);
                yield l;
            }
        });
        return updated.stop();
    }

    public boolean isTSLHit(String token, double currentPrice) {
        AtomicReference<TslState> ref = tslState.get(token);
        if (ref == null) return false;
        boolean hit = switch (ref.get()) {
            case TslState.Long  l  -> l.isHit(currentPrice);
            case TslState.Short sh -> sh.isHit(currentPrice);
        };
        if (hit) log.info("TSL HIT | {} | price:Rs.{} | stop:Rs.{}",
                token, Shared.fmt(currentPrice), Shared.fmt(ref.get().stop()));
        return hit;
    }

    public boolean isTSLHitShort(String token, double currentPrice) {
        return isTSLHit(token, currentPrice);
    }

    public double getTSL(String token) {
        AtomicReference<TslState> ref = tslState.get(token);
        return ref != null ? ref.get().stop() : 0;
    }

    public void clearTSL(String token) {
        tslState.remove(token);
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. TRADE LIFECYCLE
    // ─────────────────────────────────────────────────────────────────

    public void onTradeOpened(String token, double entryPrice, int qty) {
        onTradeOpenedWithCapital(token, entryPrice * qty, "notional");
    }

    public void onTradeOpenedWithCapital(String token, double deployedAmount, String source) {
        openPositions.incrementAndGet();
        tradesToday.incrementAndGet();
        deployedCapital.updateAndGet(v -> v + deployedAmount);
        symbolDeployed.put(token, deployedAmount);
        log.info("Trade opened | {} | deployedAmount:Rs.{} source:{} | open:{} | today:{} | deployed:Rs.{}",
                token, Shared.fmt(deployedAmount), source, openPositions.get(),
                tradesToday.get(), Shared.fmt(deployedCapital.get()));
    }

    public void restoreOpenTrade(String token, double entryPrice, int qty) {
        restoreOpenTradeWithCapital(token, entryPrice * qty, "notional");
    }

    public void restoreOpenTradeWithCapital(String token, double deployedAmount, String source) {
        openPositions.incrementAndGet();
        deployedCapital.updateAndGet(v -> v + deployedAmount);
        symbolDeployed.put(token, deployedAmount);
        log.info("Trade restored | {} | deployedAmount:Rs.{} source:{} | open:{}",
                token, Shared.fmt(deployedAmount), source, openPositions.get());
    }

    public void syncOpenPositions(int count) {
        int safeCount = Math.max(0, count);
        int previous = openPositions.getAndSet(safeCount);
        if (previous != safeCount) {
            log.info("Risk open position count synced | previous:{} current:{}", previous, safeCount);
        }
    }

    public void onTradeClosed(String token, double pnl) {
        openPositions.updateAndGet(v -> Math.max(0, v - 1));
        Double deployedValue = symbolDeployed.remove(token);
        double deployed = deployedValue == null ? 0 : deployedValue;
        if (deployed > 0) deployedCapital.updateAndGet(v -> Math.max(0, v - deployed));
        symbolPnL.merge(token, pnl, Double::sum);
        clearTSL(token);

        if (pnl < 0) {
            dailyLoss.updateAndGet(v -> v + Math.abs(pnl));
            log.info("Trade LOSS   | {} | pnl:Rs.{} | totalLoss:Rs.{}",
                    token, Shared.fmtPnl(pnl), Shared.fmt(dailyLoss.get()));
        } else {
            dailyProfit.updateAndGet(v -> v + pnl);
            log.info("Trade PROFIT | {} | pnl:Rs.{} | totalProfit:Rs.{}",
                    token, Shared.fmtPnl(pnl), Shared.fmt(dailyProfit.get()));
        }

        if (isDailyLossBreached())   haltTrading.set(true);
        if (isDailyProfitReached())  haltTrading.set(true);
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. CONTROLS
    // ─────────────────────────────────────────────────────────────────

    public void haltTrading()   { haltTrading.set(true);  log.warn("Trading MANUALLY HALTED."); }
    public void resumeTrading() { haltTrading.set(false); log.info("Trading RESUMED."); }

    public void resetDaily() {
        dailyLoss.set(0.0);
        dailyProfit.set(0.0);
        tradesToday.set(0);
        haltTrading.set(false);
        symbolPnL.clear();
        tslState.clear();
        log.info("Daily risk reset. Ready for new trading day.");
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. STATUS
    // ─────────────────────────────────────────────────────────────────

    public void printStatus() {
        log.info("------------ Risk Status ------------");
        log.info("Capital         : Rs.{}", Shared.fmt(cfg.capital()));
        log.info("Deployed        : Rs.{} / Rs.{}", Shared.fmt(deployedCapital.get()), Shared.fmt(cfg.capital()));
        log.info("Available       : Rs.{}", Shared.fmt(getAvailableCapital()));
        log.info("Daily Loss      : Rs.{} / Rs.{}", Shared.fmt(dailyLoss.get()), Shared.fmt(cfg.capital() * cfg.maxDailyLoss()));
        log.info("Daily Profit    : Rs.{}", Shared.fmt(dailyProfit.get()));
        log.info("Net P&L Today   : Rs.{}", Shared.fmtPnl(dailyProfit.get() - dailyLoss.get()));
        log.info("Trades Today    : {} / {}", tradesToday.get(), cfg.maxTradesPerDay());
        log.info("Open Positions  : {} / {}", openPositions.get(), cfg.maxOpenPositions());
        log.info("Trading Halted  : {}", haltTrading.get());
        log.info("RR Ratio        : {}", cfg.riskRewardRatio() > 0 ? "1:" + cfg.riskRewardRatio() : "OFF");
        log.info("TSL Trail       : {}", cfg.tslTrailPct() > 0 ? Shared.fmtPct(cfg.tslTrailPct()) : "OFF");
        if (!symbolPnL.isEmpty()) {
            log.info("-- Symbol P&L --");
            symbolPnL.forEach((t, pnl) -> log.info("  {} : Rs.{}", t, Shared.fmtPnl(pnl)));
        }
        log.info("-------------------------------------");
    }

    // ─────────────────────────────────────────────────────────────────
    // GETTERS
    // ─────────────────────────────────────────────────────────────────
    public double  getAvailableCapital()  { return Math.max(0, cfg.capital() - deployedCapital.get()); }
    public double  getDailyLoss()         { return dailyLoss.get(); }
    public double  getDailyProfit()       { return dailyProfit.get(); }
    public int     getTradesToday()       { return tradesToday.get(); }
    public int     getOpenPositions()     { return openPositions.get(); }
    public boolean isHalted()             { return haltTrading.get(); }
    public double  getDeployedCapital()   { return deployedCapital.get(); }
    public double  getTslTrailPct()       { return cfg.tslTrailPct(); }
    public double  getRiskRewardRatio()   { return cfg.riskRewardRatio(); }
    public RiskConfig getConfig()         { return cfg; }
    public Map<String, Double> getSymbolPnL() { return symbolPnL; }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────────────
    private boolean isDailyLossBreached() {
        return dailyLoss.get() >= cfg.capital() * cfg.maxDailyLoss();
    }

    private boolean isDailyProfitReached() {
        return cfg.maxDailyProfit() > 0
                && dailyProfit.get() >= cfg.capital() * cfg.maxDailyProfit();
    }

}
