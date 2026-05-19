package com.trading.backtest;

import com.trading.data.CandleRepository;
import com.trading.data.DatabaseConfig;
import com.trading.data.WatchlistRepository;
import com.trading.model.Candle;
import com.trading.model.WatchlistItem;
import com.trading.risk.RiskConfig;
import com.trading.strategy.HoldingType;
import com.trading.strategy.MACrossoverStrategy;
import com.trading.strategy.Strategy;
import com.trading.strategy.TradeType;
import com.trading.strategy.VwapSupertrendRsiStrategy;
import com.trading.strategy.mics.MicsConfig;
import com.trading.strategy.mics.MultiIndicatorConfluenceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CLI entry point for running backtests.
 * Usage: BacktestRunner <token> <timeframe> <strategy> <days> [VSRSI_SUPERTREND_MODE] [HOLDING_TYPE]
 *
 * Example: BacktestRunner 99926000 FIVE_MINUTE MICS 60
 * Example: BacktestRunner ALL FIVE_MINUTE VSRSI 60 REAL
 */
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: BacktestRunner <token> <timeframe> <strategy> <days>");
            return;
        }

        String token     = args[0];
        String timeframe = args[1];
        String stratName = args[2];
        int    days      = Integer.parseInt(args[3]);
        if (args.length >= 5 && "VSRSI".equalsIgnoreCase(stratName)) {
            System.setProperty("strategy.vsrsi.supertrend.mode", args[4]);
        }
        if (args.length >= 6) {
            System.setProperty("strategy." + strategyFamily(stratName) + ".holding.type", args[5]);
        }

        if ("ALL".equalsIgnoreCase(token)) {
            runAll(timeframe, stratName, days);
            return;
        }

        DatabaseConfig     db     = new DatabaseConfig();
        CandleRepository   repo   = new CandleRepository(db);

        LocalDateTime from = LocalDateTime.now().minusDays(days);
        LocalDateTime to   = LocalDateTime.now();

        List<Candle> candles = repo.findBetween(token, timeframe, from, to);
        log.info("Loaded {} candles for {} from {} to {}", candles.size(), token, from, to);

        if (candles.isEmpty()) {
            log.error("No candles found - run live/paper first to populate history.");
            db.close();
            return;
        }

        Strategy strategy = buildStrategy(stratName);
        logStrategyConfig(strategy, token, candles);

        RiskConfig     riskConfig = RiskConfig.fromAppConfig();
        BacktestEngine engine     = new BacktestEngine(strategy, riskConfig);
        BacktestEngine.BacktestResult result = engine.run(candles);
        result.print();

        db.close();
    }

    public static void runAll(String timeframe, String stratName, int days) {
        DatabaseConfig      db        = new DatabaseConfig();
        CandleRepository    repo      = new CandleRepository(db);
        WatchlistRepository wlRepo    = new WatchlistRepository(db);
        List<WatchlistItem> watchlist = wlRepo.findAll();

        if (watchlist.isEmpty()) {
            log.error("Watchlist is empty - cannot run backtest.");
            db.close();
            return;
        }

        RiskConfig riskConfig = RiskConfig.fromAppConfig();
        LocalDateTime from = LocalDateTime.now().minusDays(days);
        LocalDateTime to   = LocalDateTime.now();

        // per-symbol accumulators for summary
        int    totalTrades   = 0;
        long   totalLongs    = 0;
        long   totalShorts   = 0;
        int    totalWinners  = 0;
        int    totalLosers   = 0;
        double totalPnl      = 0;
        double totalDrawdown = 0;
        double totalGrossWin = 0;
        double totalGrossLoss= 0;
        int    profitable    = 0;
        int    ran           = 0;

        for (WatchlistItem item : watchlist) {
            if (!item.hasStrategy(stratName)) {
                log.debug("Skipping {} — not configured for {}", item.symbol(), stratName);
                continue;
            }
            List<Candle> candles = repo.findBetween(item.token(), timeframe, from, to);
            if (candles.isEmpty()) {
                log.warn("No candles for {} ({}) - skipping", item.symbol(), item.token());
                continue;
            }
            Strategy strategy = buildStrategy(stratName);
            logStrategyConfig(strategy, item.symbol(), candles);
            BacktestEngine engine = new BacktestEngine(strategy, riskConfig);
            BacktestEngine.BacktestResult r = engine.run(candles);
            log.info("=== {} ({}) ===", item.symbol(), item.token());
            r.print();

            totalTrades    += r.totalTrades();
            totalLongs     += r.longTrades();
            totalShorts    += r.shortTrades();
            totalWinners   += r.winners();
            totalLosers    += r.losers();
            totalPnl       += r.totalPnl();
            totalDrawdown  += r.maxDrawdown();
            totalGrossWin  += r.winners() * r.avgWin();
            totalGrossLoss += r.losers()  * r.avgLoss();
            if (r.totalPnl() > 0) profitable++;
            ran++;
        }

        if (ran == 0) { log.warn("No symbols had candle data."); db.close(); return; }

        double winRate      = ran > 0 ? (double) totalWinners / Math.max(totalTrades, 1) * 100 : 0;
        double profitFactor = totalGrossLoss > 0 ? totalGrossWin / totalGrossLoss : 0;

        log.info("");
        log.info("========== PORTFOLIO SUMMARY ({} days) ==========", days);
        log.info("  Symbols tested      : {}", ran);
        log.info("  Profitable symbols  : {} / {}", profitable, ran);
        log.info("  Total Trades        : {}", totalTrades);
        log.info("  Total Long Trades   : {}", totalLongs);
        log.info("  Total Short Trades  : {}", totalShorts);
        log.info("  Total Winners       : {} ({})%", totalWinners, String.format("%.1f", winRate));
        log.info("  Total Losers        : {}", totalLosers);
        log.info("  Total P&L           : Rs.{}", String.format("%+.2f", totalPnl));
        log.info("  Avg P&L per Symbol  : Rs.{}", String.format("%+.2f", totalPnl / ran));
        log.info("  Total Drawdown      : Rs.{}", String.format("%.2f", totalDrawdown));
        log.info("  Portfolio PF        : {}", totalGrossLoss > 0 ? String.format("%.2f", profitFactor) : "N/A (no losses)");
        log.info("==================================================");

        db.close();
    }

    public static Strategy buildStrategy(String stratName) {
        return switch (stratName.toUpperCase()) {
            case "MICS"  -> new MultiIndicatorConfluenceStrategy(MicsConfig.defaults(), null);
            case "MA"    -> new MACrossoverStrategy();
            case "VSRSI" -> new VwapSupertrendRsiStrategy();
            default      -> throw new IllegalArgumentException("Unknown strategy: " + stratName);
        };
    }

    private static String strategyFamily(String stratName) {
        String name = stratName == null ? "" : stratName.trim().toUpperCase();
        if ("VSRSI".equals(name)) return "vsrsi";
        if ("MICS".equals(name)) return "mics";
        if ("MA".equals(name)) return "ma";
        if ("RSI".equals(name)) return "rsi";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", ".");
    }

    private static void logStrategyConfig(Strategy strategy, String label, List<Candle> candles) {
        if (strategy instanceof VwapSupertrendRsiStrategy vsrsi) {
            VwapSupertrendRsiStrategy.Diagnostics d = vsrsi.diagnose(candles);
            log.info("VSRSI config | {} | supertrendMode={}", label, vsrsi.getSupertrendMode());
            log.info("Strategy trade type | {} | {}={}", label, strategy.getName(), TradeType.fromConfig(strategy));
            log.info("Strategy holding type | {} | {}={}", label, strategy.getName(), HoldingType.fromConfig(strategy));
            log.info("VSRSI gates  | {} | evaluated={} rawLong={} rawShort={} aboveVwap={} belowVwap={} stBull={} stBear={} bullRsi={} bearRsi={}",
                    label,
                    d.evaluatedBars(),
                    d.rawLongSignals(),
                    d.rawShortSignals(),
                    d.aboveVwap(),
                    d.belowVwap(),
                    d.stBull(),
                    d.stBear(),
                    d.bullRsi(),
                    d.bearRsi());
            log.info("VSRSI short gates | {} | belowVwap+stBear={} belowVwap+bearRsi={} stBear+bearRsi={}",
                    label,
                    d.belowVwapAndStBear(),
                    d.belowVwapAndBearRsi(),
                    d.stBearAndBearRsi());
        } else {
            log.info("Strategy trade type | {} | {}={}", label, strategy.getName(), TradeType.fromConfig(strategy));
            log.info("Strategy holding type | {} | {}={}", label, strategy.getName(), HoldingType.fromConfig(strategy));
        }
    }
}
