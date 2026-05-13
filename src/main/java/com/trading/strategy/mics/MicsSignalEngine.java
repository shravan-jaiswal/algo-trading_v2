package com.trading.strategy.mics;

import com.trading.indicator.BarSeriesCache;
import com.trading.indicator.TimeframeAggregator;
import com.trading.indicator.VwapIndicator;
import com.trading.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.List;
import java.util.StringJoiner;

public class MicsSignalEngine {

    private static final Logger log = LoggerFactory.getLogger(MicsSignalEngine.class);

    private final MicsConfig             config;
    private final VolatilityHedgeDecider hedgeDecider;
    private final int                    minCandles;

    public MicsSignalEngine(MicsConfig config) {
        this.config       = config;
        this.hedgeDecider = new VolatilityHedgeDecider(
                config.hedgeOptionThreshold(),
                config.hedgeSpreadThreshold(),
                config.atrPeriod());
        this.minCandles   = computeMinCandles(config);
    }

    public StrategySignal evaluate(List<Candle> candles5m) {
        if (candles5m == null || candles5m.size() < minCandles) {
            return StrategySignal.neutral("insufficient_candles");
        }

        Candle latest   = candles5m.get(candles5m.size() - 1);
        LocalTime t     = latest.getTs().toLocalTime();
        if (t.isBefore(config.entryStart()) || t.isAfter(config.entryCutoff())) {
            return StrategySignal.neutral("outside_entry_window");
        }

        // ── 15m trend gate ─────────────────────────────────────────────
        List<Candle>  candles15m = TimeframeAggregator.toFifteenMinutes(candles5m);
        if (candles15m.size() < config.emaSlow() + 5) {
            return StrategySignal.neutral("insufficient_15m_candles");
        }

        BarSeriesCache cache15m  = BarSeriesCache.of(candles15m);
        double ema10_15m = cache15m.ema(config.emaFast());
        double ema30_15m = cache15m.ema(config.emaSlow());

        boolean emasValid = ema10_15m > 0 && ema30_15m > 0;
        if (!emasValid) return StrategySignal.neutral("15m_emas_invalid");

        // ── Optional VWAP gate ─────────────────────────────────────────
        double  vwap          = config.useVwapGate() ? VwapIndicator.current(candles5m) : 0;
        double  close         = latest.getClose();
        boolean aboveVwap     = !config.useVwapGate() || (vwap > 0 && close > vwap);
        boolean belowVwap     = !config.useVwapGate() || (vwap > 0 && close < vwap);

        boolean trend15mBull  = ema10_15m > ema30_15m && aboveVwap;
        boolean trend15mBear  = ema10_15m < ema30_15m && belowVwap;

        if (!trend15mBull && !trend15mBear) {
            return StrategySignal.neutral("15m_trend_flat_or_vwap_mismatch");
        }

        // ── 5m entry layer — build series ONCE ────────────────────────
        BarSeriesCache cache5m = BarSeriesCache.of(candles5m);

        int bullScore = 0, bearScore = 0;
        StringJoiner bullR = new StringJoiner(",");
        StringJoiner bearR = new StringJoiner(",");

        // C1: EMA 10/30 on 5m
        double ema10_5m = cache5m.ema(config.emaFast());
        double ema30_5m = cache5m.ema(config.emaSlow());
        if (ema10_5m > 0 && ema30_5m > 0) {
            if (ema10_5m > ema30_5m) { bullScore++; bullR.add("EMA_BULL"); }
            else                      { bearScore++; bearR.add("EMA_BEAR"); }
        }

        // C2: RSI zone
        double rsi = cache5m.rsi(config.rsiPeriod());
        if (rsi > 0) {
            if (rsi > config.rsiBullLow() && rsi < config.rsiBullHigh())
                { bullScore++; bullR.add("RSI_BULL:" + String.format("%.1f", rsi)); }
            if (rsi > config.rsiBearLow() && rsi < config.rsiBearHigh())
                { bearScore++; bearR.add("RSI_BEAR:" + String.format("%.1f", rsi)); }
        }

        // C3: MACD histogram
        double macdHist = cache5m.macdHistogram(
                config.macdShort(), config.macdLong(), config.macdSignal());
        if (macdHist != 0) {
            if (macdHist > 0) { bullScore++; bullR.add("MACD_BULL"); }
            else               { bearScore++; bearR.add("MACD_BEAR"); }
        }

        // C4: Price vs BB mid
        BarSeriesCache.BollingerValues bb = cache5m.bollinger(config.bbPeriod(), config.bbK());
        if (bb.isValid()) {
            if (close > bb.mid()) { bullScore++; bullR.add("BB_BULL"); }
            else                   { bearScore++; bearR.add("BB_BEAR"); }

            // BB squeeze = direction unknown — skip entry until bands expand (breakout confirmation)
            if (bb.isSqueeze(close, config.bbSqueezeThreshold())) {
                return StrategySignal.neutral("bb_squeeze_awaiting_breakout");
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("MICS | 15m ema10:{} ema30:{} vwap:{} bull:{} | 5m ema10:{} ema30:{} " +
                      "rsi:{} macd:{} bb:{} close:{} | scores bull:{} bear:{}",
                String.format("%.2f", ema10_15m), String.format("%.2f", ema30_15m),
                String.format("%.2f", vwap), trend15mBull,
                String.format("%.2f", ema10_5m), String.format("%.2f", ema30_5m),
                String.format("%.1f", rsi), String.format("%.5f", macdHist),
                String.format("%.2f", bb.mid()), String.format("%.2f", close),
                bullScore, bearScore);
        }

        // ── ATR stop + hedge decision ─────────────────────────────────
        double atr = cache5m.atr(config.atrPeriod());

        if (trend15mBull && bullScore >= config.minConfluenceScore()) {
            double sl    = close - config.atrStopMultiplier() * atr;
            HedgeMode hm = hedgeDecider.decide(candles5m, true);
            return new StrategySignal.BullSignal(bullScore, sl, hm, bullR.toString());
        }
        if (trend15mBear && bearScore >= config.minConfluenceScore()) {
            double sl    = close + config.atrStopMultiplier() * atr;
            HedgeMode hm = hedgeDecider.decide(candles5m, false);
            return new StrategySignal.BearSignal(bearScore, sl, hm, bearR.toString());
        }

        return StrategySignal.neutral("no_confluence:bull=" + bullScore + ",bear=" + bearScore);
    }

    public int minRequiredCandles() { return minCandles; }

    private static int computeMinCandles(MicsConfig cfg) {
        int for15m = cfg.emaSlow() * 3 + 15;
        int for5m  = cfg.macdLong() + cfg.macdSignal() + cfg.bbPeriod() + 5;
        return Math.max(for15m, for5m);
    }
}
