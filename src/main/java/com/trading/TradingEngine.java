package com.trading;

import com.trading.broker.AngelOneClient;
import com.trading.broker.OptionTokenResolver;
import com.trading.broker.SmartStreamFeed;
import com.trading.config.AppConfig;
import com.trading.data.*;
import com.trading.diagnostics.TradingDashboard;
import com.trading.execution.ExitHandler;
import com.trading.execution.OrderManager;
import com.trading.execution.PaperBroker;
import com.trading.execution.TradeMonitor;
import com.trading.model.*;
import com.trading.notification.TelegramAlert;
import com.trading.notification.TelegramCommandListener;
import com.trading.risk.RiskConfig;
import com.trading.risk.RiskManager;
import com.trading.signal.SignalBus;
import com.trading.signal.SignalEvaluator;
import com.trading.signal.SignalEvent;
import com.trading.strategy.HoldingType;
import com.trading.strategy.InstrumentConfig;
import com.trading.strategy.InstrumentType;
import com.trading.strategy.Strategy;
import com.trading.strategy.Strategy.Signal;
import com.trading.strategy.mics.MultiIndicatorConfluenceStrategy;
import com.trading.strategy.MACrossoverStrategy;
import com.trading.strategy.VwapSupertrendRsiStrategy;
import com.trading.utils.MarketUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Core trading orchestrator.
 * Wires all layers together and drives the tick → signal → execute → monitor loop.
 */
public class TradingEngine {

    private static final Logger log = LoggerFactory.getLogger(TradingEngine.class);

    // ── Infrastructure ────────────────────────────────────────────────
    private final DatabaseConfig          db;
    private final CandleRepository        candleRepo;
    private final WatchlistRepository     watchlistRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final TradeLogRepository      tradeLogRepo;
    private final DailySummaryRepository  summaryRepo;
    private final StrategySignalAuditRepository signalAuditRepo;
    private final HistoricalDataFetcher   dataFetcher;

    // ── Broker ────────────────────────────────────────────────────────
    private final AngelOneClient      angelClient;
    private       SmartStreamFeed     feed;
    private       OptionTokenResolver optionResolver;

    // ── Core ──────────────────────────────────────────────────────────
    private final RiskManager      riskManager;
    private final OrderManager     orderManager;
    private final ExitHandler      exitHandler;
    private final SignalBus        signalBus;
    private final SignalEvaluator  signalEvaluator;
    private final TradeMonitor     tradeMonitor;
    private final TradingDashboard dashboard;

    // ── Watchlist & candle state ──────────────────────────────────────
    private final List<WatchlistItem> watchlist     = new ArrayList<>();
    private final TickProcessor       tickProcessor;
    private final Map<String, Double> latestLtp     = new ConcurrentHashMap<>();

    // ── Per-position state ────────────────────────────────────────────
    // All maps keyed by posKey (token|strategyName[|suffix])
    private final Map<String, Double> takeProfits      = new ConcurrentHashMap<>();
    private final Map<String, Double> stopLosses       = new ConcurrentHashMap<>();
    // Actual token/symbol/exchange for the position instrument
    // (differs from the underlying equity for options and futures)
    private final Map<String, String>        positionTokens    = new ConcurrentHashMap<>();
    private final Map<String, String>        positionSymbols   = new ConcurrentHashMap<>();
    private final Map<String, String>        positionExchanges = new ConcurrentHashMap<>();
    // DB trade records — used to persist and close trades
    private final Map<String, ExecutedTrade> openTradeRecords  = new ConcurrentHashMap<>();

    // ── Strategy name → instance (for InstrumentConfig lookup in onSignal) ──
    private final Map<String, Strategy> strategyMap = new ConcurrentHashMap<>();

    // ── Config ───────────────────────────────────────────────────────
    private final boolean paperMode;
    private final int     candleHistoryBars;
    private final String  timeframe;

    // ── Telegram ──────────────────────────────────────────────────────
    private TelegramCommandListener telegramListener;

    // ── Cycle counter (increments on every candle close, any token) ──
    private final java.util.concurrent.atomic.AtomicInteger cycleCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    // ── Shutdown ──────────────────────────────────────────────────────
    private volatile boolean running = false;

    public TradingEngine() {
        this.db             = new DatabaseConfig();
        this.candleRepo     = new CandleRepository(db);
        this.watchlistRepo  = new WatchlistRepository(db);
        this.tradeRepo      = new ExecutedTradeRepository(db);
        this.tradeLogRepo   = new TradeLogRepository(db);
        this.summaryRepo    = new DailySummaryRepository(db);
        this.signalAuditRepo = new StrategySignalAuditRepository(db);

        this.paperMode         = AppConfig.isPaperMode();
        this.timeframe         = AppConfig.get("candle.timeframe", "FIVE_MINUTE");
        this.candleHistoryBars = AppConfig.getInt("candle.history.bars", 200);

        RiskConfig riskCfg = RiskConfig.fromAppConfig();
        this.riskManager   = new RiskManager(riskCfg);
        this.riskManager.logInit();

        this.angelClient  = new AngelOneClient();
        PaperBroker paper = new PaperBroker();
        this.orderManager = new OrderManager(null, riskManager, paperMode, paper);
        this.exitHandler  = new ExitHandler(riskManager, orderManager);

        this.tickProcessor = new TickProcessor(timeframeMinutes(), candleRepo);
        this.dataFetcher   = new HistoricalDataFetcher(null, candleRepo);

        this.signalBus      = new SignalBus();
        List<Strategy> strategies = buildStrategies();
        this.signalEvaluator = new SignalEvaluator(strategies, signalBus, signalAuditRepo);
        signalBus.subscribe(this::onSignal);

        this.tradeMonitor = new TradeMonitor(riskManager, orderManager);
        this.dashboard    = new TradingDashboard(riskManager, orderManager, this::buildRuntimeSnapshot);
    }

    // ─────────────────────────────────────────────────────────────────
    // STARTUP
    // ─────────────────────────────────────────────────────────────────

    public void start() throws Exception {
        log.info("TradingEngine starting | mode={}", paperMode ? "PAPER" : "LIVE");
        running = true;

        try {
            var conn = angelClient.login();
            dataFetcher.setSmartConnect(conn);
            optionResolver = new OptionTokenResolver(conn);
            if (!paperMode) {
                orderManager.setSmartConnect(conn);
                log.info("SmartConnect injected into OrderManager (live mode).");
            }
        } catch (Exception e) {
            log.warn("Angel One login failed ({}). Historical backfill from API unavailable.", e.getMessage());
        }

        watchlist.addAll(watchlistRepo.findAll());
        log.info("Watchlist loaded: {} instruments", watchlist.size());
        if (watchlist.isEmpty()) log.warn("Watchlist is empty - nothing to trade.");

        backfillHistory();
        restoreOpenTrades();

        List<String> tokens = watchlist.stream().map(WatchlistItem::token).toList();
        feed = new SmartStreamFeed(
                angelClient.getSmartConnect(),
                angelClient.getClientId(),
                angelClient.getFeedToken(),
                this::onTick);
        feed.subscribe(tokens, "NSE");

        tradeMonitor.start(15);
        // Flush completed candles every minute without waiting for the first tick
        // of the next bar, then upsert live candles so the DB shows the current slot.
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().unstarted(r))
            .scheduleAtFixedRate(() -> {
                try {
                    var closedCandles = tickProcessor.flushCompleted();
                    for (Candle closed : closedCandles) {
                        double price = latestLtp.getOrDefault(closed.getToken(), closed.getClose());
                        onCandleClose(closed.getToken(), price);
                    }
                    tickProcessor.persistLiveSnapshots();
                } catch (Exception e) {
                    log.warn("flushCompleted error: {}", e.getMessage());
                }
            }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        if (AppConfig.getBool("health.enabled", true)) dashboard.start();

        String botToken = AppConfig.get("telegram.bot.token", "");
        String chatId   = AppConfig.get("telegram.chat.id",   "");
        if (!botToken.isEmpty() && !chatId.isEmpty()) {
            telegramListener = new TelegramCommandListener(
                    botToken, chatId, riskManager, orderManager, this::closeAllPositions,
                    strategyMap.keySet().stream().sorted().toList());
            telegramListener.start();
        }

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(this::shutdown));

        // ── Startup banner ────────────────────────────────────────────
        List<String> strategyNames = strategyMap.keySet().stream().sorted().toList();
        log.info("════════════════════════════════════════════════");
        log.info("  Mode       : {}", paperMode ? "PAPER" : "LIVE");
        log.info("  Instrument : {}", AppConfig.get("trading.instrument.type", "EQUITY"));
        log.info("  Watchlist  : {} symbols", watchlist.size());
        log.info("  Strategies : {}", strategyNames.size());
        strategyNames.forEach(n -> log.info("    → {}", n));
        log.info("════════════════════════════════════════════════");

        TelegramAlert.sendAsync(
            "✅ TradingEngine started\n" +
            "Mode: " + (paperMode ? "PAPER" : "LIVE") + "\n" +
            "Instrument: " + AppConfig.get("trading.instrument.type", "EQUITY") + "\n" +
            "Watchlist: " + watchlist.size() + " symbols\n" +
            "Strategies: " + String.join(", ", strategyNames));
        log.info("TradingEngine running.");

    }

    // ─────────────────────────────────────────────────────────────────
    // TICK HANDLER
    // ─────────────────────────────────────────────────────────────────

    private void onTick(Tick tick) {
        latestLtp.put(tick.token(), tick.ltp());
        Candle closed = tickProcessor.process(tick);
        if (closed != null) {
            onCandleClose(tick.token(), tick.ltp());
        }
    }

    private void onCandleClose(String underlyingToken, double underlyingPrice) {
        if (!MarketUtils.isMarketOpen()) return;
        if (riskManager.isHalted()) return;

        WatchlistItem item = findItem(underlyingToken);
        if (item == null) return;

        List<Candle> candles = tickProcessor.getCandles(underlyingToken);
        tickProcessor.trim(underlyingToken, candleHistoryBars + 10);

        String symbol   = item.symbol();
        String exchange = item.exchange();

        // Snapshot keys to avoid ConcurrentModificationException when positions close inside loop
        new ArrayList<>(orderManager.getOpenPositions().keySet()).forEach(posKey -> {
            if (!posKey.startsWith(underlyingToken + "|")) return;
            checkAndExit(posKey, underlyingToken, symbol, exchange, underlyingPrice);
        });

        signalEvaluator.evaluate(symbol, underlyingToken, underlyingPrice, candles, item.strategies());

        int cycle = cycleCounter.incrementAndGet();
        log.info("Cycle #{} | {} @ Rs.{} | candles:{} | open:{} | pnl:Rs.{}",
                cycle, symbol,
                String.format("%.2f", underlyingPrice),
                candles.size(),
                orderManager.getOpenPositions().size(),
                String.format("%.2f", riskManager.getDailyProfit() - riskManager.getDailyLoss()));
        if (cycle % 12 == 0) {
            riskManager.printStatus();
        }

        if (MarketUtils.isSquareOffTime()) {
            forceSquareOff(underlyingToken, symbol, exchange, underlyingPrice);
        }
    }

    /**
     * Evaluates all exit conditions for a single open position.
     * Uses the position's own token/symbol (critical for options/futures — not the underlying equity).
     */
    private void checkAndExit(String posKey, String underlyingToken,
                               String underlyingSymbol, String underlyingExchange,
                               double underlyingPrice) {
        String side = orderManager.getPositionSide(posKey);
        if (side == null) return;

        double tp = takeProfits.getOrDefault(posKey, 0.0);
        double sl = stopLosses.getOrDefault(posKey, 0.0);

        // Use the actual position instrument (option/futures token differs from underlying)
        String exitToken    = positionTokens.getOrDefault(posKey, underlyingToken);
        String exitSymbol   = positionSymbols.getOrDefault(posKey, underlyingSymbol);
        String exitExchange = positionExchanges.getOrDefault(posKey, underlyingExchange);
        // Use option/futures LTP when available; fall back to underlying price (paper mode)
        double exitPrice    = latestLtp.getOrDefault(exitToken, underlyingPrice);

        boolean exited;
        if ("BUY".equals(side)) {
            if (sl > 0 && exitPrice <= sl) {
                log.info("Fixed SL hit | {} @ Rs.{} | sl=Rs.{}", exitSymbol, exitPrice, sl);
                exited = exitHandler.forceExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, "SL");
            } else {
                exited = exitHandler.checkLongExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, tp, Signal.NONE);
            }
        } else {
            if (sl > 0 && exitPrice >= sl) {
                log.info("Fixed SL hit (short) | {} @ Rs.{} | sl=Rs.{}", exitSymbol, exitPrice, sl);
                exited = exitHandler.forceExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, "SL");
            } else {
                exited = exitHandler.checkShortExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, tp, Signal.NONE);
            }
        }
        if (exited) clearPositionState(posKey, exitPrice);
    }

    // ─────────────────────────────────────────────────────────────────
    // SIGNAL → ORDER
    // ─────────────────────────────────────────────────────────────────

    private void onSignal(SignalEvent event) {
        WatchlistItem item = findItem(event.token());
        if (item == null) return;

        Strategy strategy = strategyMap.get(event.strategyName());
        InstrumentConfig instrCfg = strategy != null
                ? strategy.getInstrumentConfig() : InstrumentConfig.EQUITY;

        if (instrCfg.isOptionBuy())        onOptionSignal(event, item, instrCfg);
        else if (instrCfg.isOptionWrite()) onOptionWriteSignal(event, item, instrCfg);
        else if (instrCfg.isFutures())     onFuturesSignal(event, item, instrCfg);
        else                               onEquitySignal(event, item);
    }

    private void onEquitySignal(SignalEvent event, WatchlistItem item) {
        String token    = event.token();
        String symbol   = item.symbol();
        String exchange = item.exchange();
        String posKey   = token + "|" + event.strategyName();
        double price    = event.currentPrice();
        Signal sig      = event.signal();

        if (sig.isLongEntry() && !orderManager.hasOpenPosition(posKey)) {
            double sl = event.suggestedStopLoss();
            if (sl <= 0) sl = riskManager.calculateStopLoss(price, calcAtr(token), 1.5, sig);
            sl = MarketUtils.roundToTick(sl, com.trading.strategy.InstrumentType.EQUITY);

            int qty = riskManager.positionSize(price, sl);
            if (qty <= 0) return;

            boolean opened = orderManager.buy(symbol, token, exchange, posKey, qty, price,
                    productTypeFor(event.strategyName(), InstrumentType.EQUITY));
            if (opened) {
                double tp = MarketUtils.roundToTick(
                        riskManager.calculateTakeProfit(price, sl, sig),
                        com.trading.strategy.InstrumentType.EQUITY);
                takeProfits.put(posKey, tp);
                positionTokens.put(posKey, token);
                positionSymbols.put(posKey, symbol);
                positionExchanges.put(posKey, exchange);
                riskManager.initTSL(posKey, price);
                saveOpenTrade(posKey, symbol, token, exchange,
                        event.strategyName(), "BUY", qty, price, sl, tp);
                log.info("Long opened | {} qty={} entry=Rs.{} sl=Rs.{} tp=Rs.{}",
                        symbol, qty, price, sl, tp);
            }

        } else if (sig.isLongExit() && orderManager.hasOpenPosition(posKey)) {
            if ("BUY".equals(orderManager.getPositionSide(posKey))) {
                boolean exited = exitHandler.checkLongExit(symbol, token, exchange, posKey, price, 0, sig);
                if (exited) clearPositionState(posKey, price);
            }
        }
    }

    private void onOptionSignal(SignalEvent event, WatchlistItem item,
                                InstrumentConfig instrCfg) {
        if (optionResolver == null) {
            log.warn("OptionTokenResolver not available - login may have failed");
            return;
        }

        String underlying = item.symbol().replaceAll("-EQ$", "");
        String stratName  = event.strategyName();
        double price      = event.currentPrice();
        Signal sig        = event.signal();

        if (sig == Signal.LONG_EXIT) {
            closePositionIfOpen(item.token() + "|" + stratName + "|CE", underlying, price);
            return;
        }
        if (sig == Signal.SHORT_EXIT) {
            closePositionIfOpen(item.token() + "|" + stratName + "|PE", underlying, price);
            return;
        }
        if (!sig.isEntry()) return;

        String optionType = sig.isLongEntry() ? "CE" : "PE";
        String posKey     = item.token() + "|" + stratName + "|" + optionType;
        if (orderManager.hasOpenPosition(posKey)) return;

        // Close opposite leg using its actual token (not the posKey string)
        String oppType   = sig.isLongEntry() ? "PE" : "CE";
        String oppPosKey = item.token() + "|" + stratName + "|" + oppType;
        closePositionIfOpen(oppPosKey, underlying, price);

        var resolution = optionResolver.resolve(underlying, price, optionType, instrCfg);
        if (resolution == null) {
            log.warn("Could not resolve {} option token for {} @ {}", optionType, underlying, price);
            return;
        }

        String optSymbol = underlying + optionType;
        int    qty       = resolution.lotSize() * instrCfg.numberOfLots();
        subscribeOptionToken(resolution.token());
        double optionPrice = tradePriceForInstrument(resolution.token(), price, optSymbol);
        if (optionPrice <= 0) return;
        log.info("Option order | {} | lotSize={} x {}lots = {} qty",
                optSymbol, resolution.lotSize(), instrCfg.numberOfLots(), qty);

        boolean opened = orderManager.buy(optSymbol, resolution.token(), "NFO", posKey, qty, optionPrice,
                productTypeFor(stratName, InstrumentType.OPTION_BUY));
        if (opened) {
            double slPct   = AppConfig.getDouble("trading.option.buy.sl.pct", 0);
            double tpPct   = AppConfig.getDouble("trading.option.buy.tp.pct", 0);
            double maxLoss = AppConfig.getDouble("trading.option.buy.max.loss", 3000);
            double slDist  = (slPct > 0) ? optionPrice * slPct : maxLoss / qty;
            double tpDist  = (tpPct > 0) ? optionPrice * tpPct : maxLoss * 2 / qty;
            double sl = MarketUtils.roundToTick(Math.max(0.05, optionPrice - slDist),
                    com.trading.strategy.InstrumentType.OPTION_BUY);
            double tp = MarketUtils.roundToTick(optionPrice + tpDist,
                    com.trading.strategy.InstrumentType.OPTION_BUY);
            takeProfits.put(posKey, tp);
            stopLosses.put(posKey, sl);
            positionTokens.put(posKey, resolution.token());
            positionSymbols.put(posKey, optSymbol);
            positionExchanges.put(posKey, "NFO");
            saveOpenTrade(posKey, optSymbol, resolution.token(), "NFO",
                    stratName, "BUY", qty, optionPrice, sl, tp);
            log.info("Option BUY | {} token={} qty={} @ Rs.{} | tp=Rs.{} sl=Rs.{} [{}]",
                    optSymbol, resolution.token(), qty, optionPrice, tp, sl,
                    slPct > 0 ? String.format("pct sl=%.0f%% tp=%.0f%%", slPct * 100, tpPct * 100)
                              : String.format("fixed sl=-Rs.%.0f tp=+Rs.%.0f", maxLoss, maxLoss * 2));
        }
    }

    private void onOptionWriteSignal(SignalEvent event, WatchlistItem item,
                                     InstrumentConfig instrCfg) {
        if (optionResolver == null) {
            log.warn("OptionTokenResolver not available - login may have failed");
            return;
        }

        String underlying = item.symbol().replaceAll("-EQ$", "");
        String stratName  = event.strategyName();
        double price      = event.currentPrice();
        Signal sig        = event.signal();

        // Bullish → write PE (sell put); bearish → write CE (sell call)
        if (sig == Signal.LONG_EXIT) {
            closePositionIfOpen(item.token() + "|" + stratName + "|WRITE_PE", underlying, price);
            return;
        }
        if (sig == Signal.SHORT_EXIT) {
            closePositionIfOpen(item.token() + "|" + stratName + "|WRITE_CE", underlying, price);
            return;
        }
        if (!sig.isEntry()) return;

        // Bullish -> write PE (sell put); bearish -> write CE (sell call)
        String optionType = sig.isLongEntry() ? "PE" : "CE";
        String posKey     = item.token() + "|" + stratName + "|WRITE_" + optionType;
        if (orderManager.hasOpenPosition(posKey)) return;

        // Close opposite write leg using its actual token
        String oppType   = sig.isLongEntry() ? "CE" : "PE";
        String oppPosKey = item.token() + "|" + stratName + "|WRITE_" + oppType;
        closePositionIfOpen(oppPosKey, underlying, price);

        var resolution = optionResolver.resolve(underlying, price, optionType, instrCfg);
        if (resolution == null) {
            log.warn("Could not resolve {} option token for write on {} @ {}",
                    optionType, underlying, price);
            return;
        }

        String optSymbol = underlying + optionType;
        int    qty       = resolution.lotSize() * instrCfg.numberOfLots();
        subscribeOptionToken(resolution.token());
        double optionPrice = tradePriceForInstrument(resolution.token(), price, optSymbol);
        if (optionPrice <= 0) return;
        boolean opened = orderManager.sellShort(optSymbol, resolution.token(), "NFO", posKey, qty, optionPrice,
                productTypeFor(stratName, InstrumentType.OPTION_WRITE));
        if (opened) {
            double maxProfit = AppConfig.getDouble("trading.option.write.max.profit", 6000);
            double maxLoss   = AppConfig.getDouble("trading.option.write.max.loss",   3000);
            // Short: profit when premium falls (buy back cheaper), loss when it rises
            double tp = MarketUtils.roundToTick(Math.max(0.05, optionPrice - maxProfit / qty),
                    com.trading.strategy.InstrumentType.OPTION_WRITE);
            double sl = MarketUtils.roundToTick(optionPrice + maxLoss / qty,
                    com.trading.strategy.InstrumentType.OPTION_WRITE);
            takeProfits.put(posKey, tp);
            stopLosses.put(posKey, sl);
            positionTokens.put(posKey, resolution.token());
            positionSymbols.put(posKey, optSymbol);
            positionExchanges.put(posKey, "NFO");
            saveOpenTrade(posKey, optSymbol, resolution.token(), "NFO",
                    stratName, "SELL", qty, optionPrice, sl, tp);
            log.info("Option WRITE | {} token={} qty={} @ Rs.{} | buyback_tp=Rs.{} (max+{}) sl=Rs.{} (max-{})",
                    optSymbol, resolution.token(), qty, optionPrice,
                    tp, (int) maxProfit, sl, (int) maxLoss);
        }
    }

    private void onFuturesSignal(SignalEvent event, WatchlistItem item,
                                 InstrumentConfig instrCfg) {
        if (optionResolver == null) {
            log.warn("OptionTokenResolver not available - login may have failed");
            return;
        }

        String underlying = item.symbol().replaceAll("-EQ$", "");
        String stratName  = event.strategyName();
        double price      = event.currentPrice();
        Signal sig        = event.signal();
        String posKey     = item.token() + "|" + stratName + "|FUT";

        var resolution = optionResolver.resolveFutures(underlying, instrCfg.expiryOffset());
        if (resolution == null) {
            log.warn("Could not resolve futures token for {}", underlying);
            return;
        }

        String futToken  = resolution.token();
        String futSymbol = underlying + "FUT";
        int    qty       = resolution.lotSize() * instrCfg.numberOfLots();
        subscribeOptionToken(futToken);
        double futPrice = tradePriceForInstrument(futToken, price, futSymbol);
        if (futPrice <= 0) return;

        if (sig == Signal.LONG_EXIT) {
            if (orderManager.hasOpenPosition(posKey)
                    && "BUY".equals(orderManager.getPositionSide(posKey))) {
                boolean exited = exitHandler.checkLongExit(
                        futSymbol, futToken, "NFO", posKey, futPrice, 0, sig);
                if (exited) clearPositionState(posKey, futPrice);
            }
            return;
        }
        if (sig == Signal.SHORT_EXIT) {
            if (orderManager.hasOpenPosition(posKey)
                    && "SELL".equals(orderManager.getPositionSide(posKey))) {
                boolean exited = exitHandler.checkShortExit(
                        futSymbol, futToken, "NFO", posKey, futPrice, 0, sig);
                if (exited) clearPositionState(posKey, futPrice);
            }
            return;
        }
        if (!sig.isEntry()) return;

        if (sig.isLongEntry()) {
            if (orderManager.hasOpenPosition(posKey)) {
                if ("SELL".equals(orderManager.getPositionSide(posKey))) {
                    boolean exited = exitHandler.checkShortExit(
                            futSymbol, futToken, "NFO", posKey, futPrice, 0, sig);
                    if (exited) clearPositionState(posKey, futPrice);
                }
                return;
            }
            double sl = MarketUtils.roundToTick(event.suggestedStopLoss(),
                    com.trading.strategy.InstrumentType.FUTURES);
            if (sl <= 0) sl = MarketUtils.roundToTick(
                    riskManager.calculateStopLoss(futPrice, calcAtr(item.token()), 1.5, sig),
                    com.trading.strategy.InstrumentType.FUTURES);
            boolean opened = orderManager.buy(futSymbol, futToken, "NFO", posKey, qty, futPrice,
                    productTypeFor(stratName, InstrumentType.FUTURES));
            if (opened) {
                double tp = MarketUtils.roundToTick(
                        riskManager.calculateTakeProfit(futPrice, sl, sig),
                        com.trading.strategy.InstrumentType.FUTURES);
                takeProfits.put(posKey, tp);
                positionTokens.put(posKey, futToken);
                positionSymbols.put(posKey, futSymbol);
                positionExchanges.put(posKey, "NFO");
                riskManager.initTSL(posKey, futPrice);
                saveOpenTrade(posKey, futSymbol, futToken, "NFO",
                        stratName, "BUY", qty, futPrice, sl, tp);
                log.info("Futures LONG | {} token={} qty={} @ Rs.{} sl=Rs.{} tp=Rs.{}",
                        underlying, futToken, qty, futPrice, sl, tp);
            }

        } else if (sig.isShortEntry()) {
            if (orderManager.hasOpenPosition(posKey)) {
                if ("BUY".equals(orderManager.getPositionSide(posKey))) {
                    boolean exited = exitHandler.checkLongExit(
                            futSymbol, futToken, "NFO", posKey, futPrice, 0, sig);
                    if (exited) clearPositionState(posKey, futPrice);
                }
                return;
            }
            double sl = MarketUtils.roundToTick(event.suggestedStopLoss(),
                    com.trading.strategy.InstrumentType.FUTURES);
            if (sl <= 0) sl = MarketUtils.roundToTick(
                    riskManager.calculateStopLoss(futPrice, calcAtr(item.token()), 1.5, sig),
                    com.trading.strategy.InstrumentType.FUTURES);
            boolean opened = orderManager.sellShort(futSymbol, futToken, "NFO", posKey, qty, futPrice,
                    productTypeFor(stratName, InstrumentType.FUTURES));
            if (opened) {
                double tp = MarketUtils.roundToTick(
                        riskManager.calculateTakeProfit(futPrice, sl, sig),
                        com.trading.strategy.InstrumentType.FUTURES);
                takeProfits.put(posKey, tp);
                positionTokens.put(posKey, futToken);
                positionSymbols.put(posKey, futSymbol);
                positionExchanges.put(posKey, "NFO");
                riskManager.initTSLShort(posKey, futPrice);
                saveOpenTrade(posKey, futSymbol, futToken, "NFO",
                        stratName, "SELL", qty, futPrice, sl, tp);
                log.info("Futures SHORT | {} token={} qty={} @ Rs.{} sl=Rs.{} tp=Rs.{}",
                        underlying, futToken, qty, futPrice, sl, tp);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // EOD SQUARE-OFF
    // ─────────────────────────────────────────────────────────────────

    private double tradePriceForInstrument(String token, double fallbackPrice, String symbol) {
        Double known = latestLtp.get(token);
        if (known != null && known > 0) return known;

        if (!paperMode) {
            long deadline = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < deadline) {
                known = latestLtp.get(token);
                if (known != null && known > 0) return known;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("No live LTP for {} token={} - skipping order to avoid using underlying price",
                    symbol, token);
            return 0;
        }

        return fallbackPrice;
    }

    private JSONObject buildRuntimeSnapshot() {
        JSONArray candleState = new JSONArray();
        for (WatchlistItem item : watchlist) {
            List<Candle> candles = tickProcessor.getCandles(item.token());
            Candle latest = candles.isEmpty() ? null : candles.get(candles.size() - 1);
            candleState.put(new JSONObject()
                    .put("token", item.token())
                    .put("symbol", item.symbol())
                    .put("lastLtp", latestLtp.getOrDefault(item.token(), 0.0))
                    .put("candleCount", candles.size())
                    .put("latestCandleTs", latest != null ? latest.getTs().toString() : JSONObject.NULL)
                    .put("latestClose", latest != null ? latest.getClose() : 0.0)
                    .put("latestVolume", latest != null ? latest.getVolume() : 0.0));
        }

        JSONArray auditState = new JSONArray();
        signalEvaluator.lastAudits().values().stream()
                .sorted((a, b) -> a.symbol().compareToIgnoreCase(b.symbol()))
                .forEach(a -> auditState.put(new JSONObject()
                        .put("token", a.token())
                        .put("symbol", a.symbol())
                        .put("strategy", a.strategyName())
                        .put("candleTs", a.candleTs() != null ? a.candleTs().toString() : JSONObject.NULL)
                        .put("signal", a.signal())
                        .put("reason", a.reason())
                        .put("rsi", a.rsi())
                        .put("vwap", a.vwap())
                        .put("supertrend", a.supertrend())
                        .put("adx", a.adx())
                        .put("volumeRatio", a.volumeRatio())
                        .put("bullScore", a.bullScore())
                        .put("bearScore", a.bearScore())));

        JSONArray positions = new JSONArray();
        orderManager.getOpenPositions().forEach((key, pos) -> positions.put(new JSONObject()
                .put("key", key)
                .put("symbol", pos.symbol())
                .put("side", pos.side())
                .put("qty", pos.qty())
                .put("entryPrice", pos.entryPrice())
                .put("ltp", latestLtp.getOrDefault(positionTokens.getOrDefault(key, key), 0.0))));

        return new JSONObject()
                .put("candles", candleState)
                .put("lastSignals", auditState)
                .put("positions", positions);
    }

    private void closeAllPositions() {
        log.warn("closeAllPositions triggered via Telegram /closeall");
        new ArrayList<>(orderManager.getOpenPositions().keySet()).forEach(posKey -> {
            String exitToken    = positionTokens.getOrDefault(posKey, posKey);
            String exitSymbol   = positionSymbols.getOrDefault(posKey, posKey);
            String exitExchange = positionExchanges.getOrDefault(posKey, "NSE");
            double exitPrice    = latestLtp.getOrDefault(exitToken, 0.0);
            if (exitPrice <= 0) { log.warn("closeAll: no LTP for {} — skipping", posKey); return; }
            boolean exited = exitHandler.forceExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, "MANUAL_EXIT");
            if (exited) clearPositionState(posKey, exitPrice);
        });
        TelegramAlert.sendAsync("All positions closed via /closeall.");
    }

    private void forceSquareOff(String underlyingToken, String symbol, String exchange, double underlyingPrice) {
        new ArrayList<>(orderManager.getOpenPositions().keySet()).forEach(posKey -> {
            if (!posKey.startsWith(underlyingToken + "|")) return;
            if (!isIntradayPosition(posKey)) {
                log.debug("Skipping delivery position at square-off | {}", posKey);
                return;
            }
            String exitToken    = positionTokens.getOrDefault(posKey, underlyingToken);
            String exitSymbol   = positionSymbols.getOrDefault(posKey, symbol);
            String exitExchange = positionExchanges.getOrDefault(posKey, exchange);
            double exitPrice    = latestLtp.getOrDefault(exitToken, underlyingPrice);
            boolean exited = exitHandler.forceExit(exitSymbol, exitToken, exitExchange, posKey, exitPrice, "INTRADAY_EXIT");
            if (exited) clearPositionState(posKey, exitPrice);
        });
    }

    private boolean isIntradayPosition(String posKey) {
        String[] parts = posKey.split("\\|");
        if (parts.length < 2) return true;
        return HoldingType.fromStrategyName(parts[1]).isIntraday();
    }

    private String productTypeFor(String strategyName, InstrumentType instrumentType) {
        HoldingType holdingType = HoldingType.fromStrategyName(strategyName);
        if (holdingType.isIntraday()) {
            return AppConfig.get("order.product.type.intraday", "INTRADAY");
        }

        if (instrumentType == InstrumentType.EQUITY) {
            return AppConfig.get("order.product.type.delivery.equity", "DELIVERY");
        }

        String legacyFallback = AppConfig.get("order.product.type", "CARRYFORWARD");
        return AppConfig.get("order.product.type.delivery.derivatives", legacyFallback);
    }

    private String productTypeForStrategyName(String strategyName) {
        Strategy strategy = strategyMap.get(strategyName);
        InstrumentType instrumentType = strategy != null
                ? strategy.getInstrumentConfig().type()
                : InstrumentType.EQUITY;
        return productTypeFor(strategyName, instrumentType);
    }

    // ─────────────────────────────────────────────────────────────────
    // POSITION STATE
    // ─────────────────────────────────────────────────────────────────

    /** Closes a position using its tracked token (correct for options/futures). */
    private void closePositionIfOpen(String posKey, String fallbackSymbol, double fallbackPrice) {
        if (!orderManager.hasOpenPosition(posKey)) return;
        String exitToken  = positionTokens.getOrDefault(posKey, posKey);
        String exitSymbol = positionSymbols.getOrDefault(posKey, fallbackSymbol);
        String exitExch   = positionExchanges.getOrDefault(posKey, "NFO");
        double exitPrice  = latestLtp.getOrDefault(exitToken, fallbackPrice);
        boolean closed = orderManager.closePosition(exitSymbol, exitToken, exitExch, posKey, exitPrice);
        if (closed) clearPositionState(posKey, exitPrice, "SIGNAL");
    }

    /** Persists a new open trade to DB and registers it in openTradeRecords. */
    private void saveOpenTrade(String posKey, String symbol, String token, String exchange,
                               String strategyName, String side, int qty,
                               double entryPrice, double sl, double tp) {
        ExecutedTrade t = new ExecutedTrade();
        t.setPosKey(posKey);
        t.setToken(token);
        t.setSymbol(symbol);
        t.setStrategyName(strategyName);
        t.setEntryPrice(entryPrice);
        t.setQuantity(qty);
        t.setSide(side);
        t.setStatus("OPEN");
        t.setStopLoss(sl);
        t.setTakeProfit(tp);
        t.setExchange(exchange);
        t.setEntryTime(LocalDateTime.now(MarketUtils.IST));
        tradeRepo.save(t);
        openTradeRecords.put(posKey, t);
    }

    /** Persists the trade close to DB and removes all per-position state. */
    private void clearPositionState(String posKey, double exitPrice) {
        clearPositionState(posKey, exitPrice, exitHandler.consumeLastExitReason(posKey, "UNKNOWN"));
    }

    private void clearPositionState(String posKey, double exitPrice, String exitReason) {
        ExecutedTrade trade = openTradeRecords.remove(posKey);
        if (trade != null) {
            double pnl = "BUY".equals(trade.getSide())
                    ? (exitPrice - trade.getEntryPrice()) * trade.getQuantity()
                    : (trade.getEntryPrice() - exitPrice) * trade.getQuantity();
            trade.setExitPrice(exitPrice);
            trade.setExitTime(LocalDateTime.now(MarketUtils.IST));
            trade.setPnl(pnl);
            trade.setStatus("CLOSED");
            trade.setExitReason(exitReason);
            tradeRepo.close(trade);
        }
        takeProfits.remove(posKey);
        stopLosses.remove(posKey);
        positionTokens.remove(posKey);
        positionSymbols.remove(posKey);
        positionExchanges.remove(posKey);
    }

    // ─────────────────────────────────────────────────────────────────
    // STARTUP HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void backfillHistory() {
        if (watchlist.isEmpty()) return;
        int days = Math.max(AppConfig.getInt("candle.history.bars", 200) / 75, 5);

        for (WatchlistItem item : watchlist) {
            try {
                var historical = candleRepo.findRecent(item.token(), timeframe, candleHistoryBars);

                // Fetch from API if connected and there is a gap (last candle is older than one interval)
                if (dataFetcher.isConnected()) {
                    LocalDateTime lastTs = historical.isEmpty()
                            ? null
                            : historical.get(historical.size() - 1).getTs();
                    LocalDateTime now = LocalDateTime.now(MarketUtils.IST);
                    boolean hasGap = lastTs == null
                            || lastTs.isBefore(now.minusMinutes(timeframeMinutes()));

                    if (hasGap || historical.size() < candleHistoryBars / 2) {
                        log.info("Gap detected for {} — fetching from API (last candle: {})",
                                item.symbol(), lastTs);
                        var fetched = dataFetcher.backfill(item, timeframe, days);
                        if (!fetched.isEmpty()) {
                            // Re-read DB so the seed includes both old + newly fetched candles
                            historical = candleRepo.findRecent(item.token(), timeframe, candleHistoryBars);
                        }
                        try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }

                tickProcessor.seed(item.token(), historical);
            } catch (Exception e) {
                log.error("Backfill failed for {}: {}", item.symbol(), e.getMessage());
            }
        }
    }

    private void restoreOpenTrades() {
        List<ExecutedTrade> open = tradeRepo.findOpen();
        for (ExecutedTrade t : open) {
            String posKey = (t.getPosKey() != null && !t.getPosKey().isBlank())
                    ? t.getPosKey()
                    : t.getToken() + "|" + t.getStrategyName(); // legacy fallback for old DB rows
            orderManager.restorePosition(posKey, t.getEntryPrice(), t.getQuantity(), t.getSide(),
                    t.getSymbol(), productTypeForStrategyName(t.getStrategyName()));
            riskManager.restoreOpenTrade(posKey, t.getEntryPrice(), t.getQuantity());

            // Restore position state maps
            positionTokens.put(posKey, t.getToken());
            positionSymbols.put(posKey, t.getSymbol());
            positionExchanges.put(posKey, t.getExchange() != null ? t.getExchange() : "NSE");
            if (t.getTakeProfit() > 0) takeProfits.put(posKey, t.getTakeProfit());
            if (t.getStopLoss()   > 0) stopLosses.put(posKey, t.getStopLoss());
            openTradeRecords.put(posKey, t);  // restored trades can be closed to DB on exit

            if ("BUY".equals(t.getSide()) && riskManager.getTslTrailPct() > 0) {
                riskManager.initTSL(posKey, t.getEntryPrice());
            } else if ("SELL".equals(t.getSide()) && riskManager.getTslTrailPct() > 0) {
                riskManager.initTSLShort(posKey, t.getEntryPrice());
            }

            log.info("Restored open trade: {} {} x{} @ Rs.{}",
                    t.getSide(), t.getSymbol(), t.getQuantity(), t.getEntryPrice());
        }
        if (!open.isEmpty()) log.info("{} open trades restored from DB.", open.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // SHUTDOWN
    // ─────────────────────────────────────────────────────────────────

    public void shutdown() {
        if (!running) return;
        running = false;
        log.info("TradingEngine shutting down...");

        latestLtp.forEach((token, price) -> {
            WatchlistItem item = findItem(token);
            if (item != null) forceSquareOff(token, item.symbol(), item.exchange(), price);
        });

        tickProcessor.flush();   // save the in-progress bar so it's not lost on restart
        tradeMonitor.stop();
        dashboard.stop();
        if (telegramListener != null) telegramListener.stop();
        if (!paperMode) angelClient.logout();
        db.close();

        TelegramAlert.send("TradingEngine STOPPED.");
        log.info("TradingEngine stopped.");
    }

    // ─────────────────────────────────────────────────────────────────
    // PAPER MODE LOOP
    // ─────────────────────────────────────────────────────────────────

    private void runPaperLoop() throws InterruptedException {
        log.info("Paper mode - replaying candles from DB.");
        for (WatchlistItem item : watchlist) {
            List<Candle> candles = candleRepo.findRecent(item.token(), timeframe, candleHistoryBars);
            for (Candle c : candles) {
                double price = c.getClose();
                latestLtp.put(item.token(), price);
                onCandleClose(item.token(), price);
            }
        }
        log.info("Paper replay complete.");
        riskManager.printStatus();
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private List<Strategy> buildStrategies() {
        String instrType = AppConfig.get("trading.instrument.type", "EQUITY").toUpperCase();
        InstrumentConfig micsInstr;

        if ("OPTION_BUY".equals(instrType)) {
            int expiryOffset = AppConfig.getInt("trading.option.expiry.offset", 0);
            int strikeOffset = AppConfig.getInt("trading.option.strike.offset", 0);
            int numberOfLots = AppConfig.getInt("trading.option.lots",          1);
            micsInstr = InstrumentConfig.optionBuy(expiryOffset, strikeOffset, numberOfLots);
            log.info("Instrument: OPTION_BUY | expiry={} strike={} lots={}",
                    expiryOffset, strikeOffset, numberOfLots);

        } else if ("OPTION_WRITE".equals(instrType)) {
            int expiryOffset = AppConfig.getInt("trading.option.expiry.offset", 0);
            int strikeOffset = AppConfig.getInt("trading.option.strike.offset", 1); // OTM default for writes
            int numberOfLots = AppConfig.getInt("trading.option.lots",          1);
            micsInstr = InstrumentConfig.optionWrite(expiryOffset, strikeOffset, numberOfLots);
            log.info("Instrument: OPTION_WRITE | expiry={} strike={} lots={}",
                    expiryOffset, strikeOffset, numberOfLots);

        } else if ("FUTURES".equals(instrType)) {
            int expiryOffset = AppConfig.getInt("trading.futures.expiry.offset", 0);
            int numberOfLots = AppConfig.getInt("trading.futures.lots",          1);
            micsInstr = new InstrumentConfig(
                    com.trading.strategy.InstrumentType.FUTURES, "NFO",
                    expiryOffset, 0, numberOfLots, true);
            log.info("Instrument: FUTURES | expiry={} lots={}", expiryOffset, numberOfLots);

        } else {
            micsInstr = InstrumentConfig.EQUITY;
        }

        List<Strategy> list = new ArrayList<>();

        // ── VSRSI — always FUTURES ────────────────────────────────────
        InstrumentConfig vsrsiInstr = new InstrumentConfig(
                com.trading.strategy.InstrumentType.FUTURES, "NFO",
                AppConfig.getInt("trading.futures.expiry.offset", 0),
                0,
                AppConfig.getInt("trading.futures.lots", 1),
                true);

        list.add(new VwapSupertrendRsiStrategy(
                AppConfig.getInt(   "strategy.vsrsi.atr.period",      10),
                AppConfig.getDouble("strategy.vsrsi.atr.multiplier",  3.0),
                AppConfig.getInt(   "strategy.vsrsi.rsi.period",      14),
                AppConfig.getDouble("strategy.vsrsi.rsi.bull.low",    40),
                AppConfig.getDouble("strategy.vsrsi.rsi.bull.high",   70),
                AppConfig.getDouble("strategy.vsrsi.rsi.bear.low",    30),
                AppConfig.getDouble("strategy.vsrsi.rsi.bear.high",   60),
                java.time.LocalTime.parse(AppConfig.get("strategy.vsrsi.entry.start",  "09:30")),
                java.time.LocalTime.parse(AppConfig.get("strategy.vsrsi.entry.cutoff", "14:30")),
                vsrsiInstr,
                VwapSupertrendRsiStrategy.SupertrendMode.parse(
                        AppConfig.get("strategy.vsrsi.supertrend.mode", "LEGACY"))));

        // ── MICS — always OPTION_BUY ──────────────────────────────────
        InstrumentConfig micsOptionInstr = InstrumentConfig.optionBuy(
                AppConfig.getInt("trading.option.expiry.offset", 0),
                AppConfig.getInt("trading.option.strike.offset", 0),
                AppConfig.getInt("trading.option.lots",          1));
        list.add(new MultiIndicatorConfluenceStrategy(
                com.trading.strategy.mics.MicsConfig.fromAppConfig(), micsOptionInstr));
        list.forEach(s -> strategyMap.put(s.getName(), s));
        return list;
    }

    private WatchlistItem findItem(String token) {
        for (WatchlistItem item : watchlist) {
            if (item.token().equals(token)) return item;
        }
        return null;
    }

    private int timeframeMinutes() {
        return switch (timeframe) {
            case "ONE_MINUTE"     -> 1;
            case "THREE_MINUTE"   -> 3;
            case "FIVE_MINUTE"    -> 5;
            case "FIFTEEN_MINUTE" -> 15;
            default               -> 5;
        };
    }

    private double calcAtr(String token) {
        var candles = tickProcessor.getCandles(token);
        if (candles.size() < 15) return 0;
        return com.trading.indicator.BarSeriesCache.of(candles).atr(14);
    }

    /**
     * Adds an option or futures token to the live WebSocket feed after a position is opened.
     * Uses NFO exchange type. No-op in paper mode.
     */
    private void subscribeOptionToken(String token) {
        if (paperMode || feed == null) return;
        try {
            feed.subscribe(List.of(token), "NFO");
        } catch (Exception e) {
            log.warn("Could not subscribe option/futures token {} to feed: {}", token, e.getMessage());
        }
    }
}
