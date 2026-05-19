package com.trading.diagnostics;

import com.trading.backtest.BacktestRunner;
import com.trading.data.CandleRepository;
import com.trading.data.DatabaseConfig;
import com.trading.data.WatchlistRepository;
import com.trading.model.Candle;
import com.trading.model.StrategySignalAudit;
import com.trading.model.WatchlistItem;
import com.trading.signal.SignalBus;
import com.trading.signal.SignalEvaluator;
import com.trading.strategy.Strategy;
import com.trading.utils.MarketUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class TodayReplayRunner {

    private static final Logger log = LoggerFactory.getLogger(TodayReplayRunner.class);

    private TodayReplayRunner() {}

    public static void run(String timeframe, String strategyName, String tokenFilter) {
        JSONObject result = replay(timeframe, strategyName, tokenFilter);
        result.optJSONArray("symbols").forEach(row -> {
            JSONObject item = (JSONObject) row;
            JSONArray signals = item.getJSONArray("signals");
            signals.forEach(signalRow -> {
                JSONObject signal = (JSONObject) signalRow;
                log.info("Replay SIGNAL | {} | {} {} @ Rs.{} | sl=Rs.{}",
                        signal.getString("symbol"),
                        signal.getString("strategy"),
                        signal.getString("signal"),
                        signal.getDouble("price"),
                        signal.getDouble("stopLoss"));
            });
            log.info("Replay summary | {} | candles={} signals={} lastSignal={} reason={}",
                    item.getString("symbol"),
                    item.getInt("candles"),
                    item.getInt("signalCount"),
                    item.getString("lastSignal"),
                    item.getString("reason"));
        });
        log.info("Replay complete | strategy={} symbols={} totalSignals={}",
                result.getString("strategy"),
                result.getInt("symbolCount"),
                result.getInt("totalSignals"));
    }

    public static JSONObject replay(String timeframe, String strategyName, String tokenFilter) {
        DatabaseConfig db = new DatabaseConfig();
        try {
            CandleRepository candleRepo = new CandleRepository(db);
            WatchlistRepository watchlistRepo = new WatchlistRepository(db);
            List<WatchlistItem> watchlist = watchlistRepo.findAll();

            LocalDate today = LocalDate.now(MarketUtils.IST);
            LocalDateTime from = today.atStartOfDay();
            LocalDateTime to = LocalDateTime.now(MarketUtils.IST);

            JSONArray symbolResults = new JSONArray();
            int totalSignals = 0;

            for (WatchlistItem item : watchlist) {
                if (tokenFilter != null && !tokenFilter.equals(item.token())) continue;
                if (!item.hasStrategy(strategyName)) continue;

                List<Candle> candles = candleRepo.findBetween(item.token(), timeframe, from, to);
                if (candles.isEmpty()) {
                    log.warn("Replay | {} | no candles today", item.symbol());
                    continue;
                }

                Strategy strategy = BacktestRunner.buildStrategy(strategyName);
                SignalBus bus = new SignalBus();
                AtomicInteger signalCount = new AtomicInteger();
                JSONArray signals = new JSONArray();
                bus.subscribe(event -> {
                    signalCount.incrementAndGet();
                    signals.put(new JSONObject()
                            .put("symbol", event.symbol())
                            .put("token", event.token())
                            .put("strategy", event.strategyName())
                            .put("signal", event.signal().name())
                            .put("price", event.currentPrice())
                            .put("stopLoss", event.suggestedStopLoss())
                            .put("evaluatedAt", event.timestamp().toString()));
                });

                SignalEvaluator evaluator = new SignalEvaluator(List.of(strategy), bus);
                Set<String> allowed = Set.of(strategyName.toUpperCase());
                for (int i = 0; i < candles.size(); i++) {
                    List<Candle> window = candles.subList(0, i + 1);
                    Candle bar = candles.get(i);
                    evaluator.evaluate(item.symbol(), item.token(), bar.getClose(), window, allowed);
                }

                StrategySignalAudit last = evaluator.lastAudits()
                        .get(item.token() + "|" + strategy.getName());

                symbolResults.put(new JSONObject()
                        .put("symbol", item.symbol())
                        .put("token", item.token())
                        .put("candles", candles.size())
                        .put("signalCount", signalCount.get())
                        .put("lastSignal", last != null ? last.signal() : "NONE")
                        .put("reason", last != null ? last.reason() : "not_evaluated")
                        .put("lastCandleTs", candles.get(candles.size() - 1).getTs().toString())
                        .put("signals", signals));

                totalSignals += signalCount.get();
            }

            return new JSONObject()
                    .put("strategy", strategyName)
                    .put("timeframe", timeframe)
                    .put("token", tokenFilter != null ? tokenFilter : JSONObject.NULL)
                    .put("from", from.toString())
                    .put("to", to.toString())
                    .put("symbolCount", symbolResults.length())
                    .put("totalSignals", totalSignals)
                    .put("symbols", symbolResults);
        } finally {
            db.close();
        }
    }
}
