package com.trading;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point — keeps wiring minimal; all logic lives in TradingEngine.
 *
 * Usage:
 *   java -jar algo-trading-v2.jar                        # live/paper based on config
 *   java -jar algo-trading-v2.jar --mode paper           # force paper mode
 *   java -jar algo-trading-v2.jar --backtest 60 MICS     # backtest 60 days
 *   java -jar algo-trading-v2.jar --replay-today -s MICS # replay today's candles
 */
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    @Parameter(names = {"--mode", "-m"}, description = "Trading mode: paper | live")
    private String mode;

    @Parameter(names = {"--backtest", "-b"}, description = "Backtest days")
    private int backtestDays = 0;

    @Parameter(names = {"--strategy", "-s"}, description = "Strategy for backtest")
    private String backtestStrategy = "MICS";

    @Parameter(names = {"--token", "-t"}, description = "Token for backtest (default: all watchlist)")
    private String backtestToken = null;

    @Parameter(names = {"--replay-today"}, description = "Replay today's DB candles through a strategy without trading")
    private boolean replayToday = false;

    @Parameter(names = {"--help", "-h"}, help = true)
    private boolean help;

    public static void main(String[] args) {
        Application app = new Application();
        JCommander jc = JCommander.newBuilder().addObject(app).build();
        jc.parse(args);

        if (app.help) {
            jc.usage();
            return;
        }

        if (app.mode != null) {
            System.setProperty("trading.mode", app.mode);
        }

        if (app.backtestDays > 0) {
            log.info("Starting backtest | strategy={} days={} token={}",
                     app.backtestStrategy, app.backtestDays,
                     app.backtestToken != null ? app.backtestToken : "ALL");
            if (app.backtestToken != null) {
                com.trading.backtest.BacktestRunner.main(
                        new String[]{app.backtestToken, "FIVE_MINUTE", app.backtestStrategy,
                                     String.valueOf(app.backtestDays)});
            } else {
                com.trading.backtest.BacktestRunner.runAll(
                        "FIVE_MINUTE", app.backtestStrategy, app.backtestDays);
            }
            return;
        }

        if (app.replayToday) {
            log.info("Starting today replay | strategy={} token={}",
                    app.backtestStrategy,
                    app.backtestToken != null ? app.backtestToken : "ALL");
            com.trading.diagnostics.TodayReplayRunner.run(
                    "FIVE_MINUTE", app.backtestStrategy, app.backtestToken);
            return;
        }

        log.info("Starting Algo Trading System v2.0");

        TradingEngine engine = new TradingEngine();
        try {
            engine.start();
        } catch (Exception e) {
            log.error("Fatal error during startup: {}", e.getMessage(), e);
            engine.shutdown();
            System.exit(1);
        }
    }
}
