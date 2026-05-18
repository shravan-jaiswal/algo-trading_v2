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

/**
 * MICS v2 signal engine.
 *
 * Gate layer (must ALL pass before scoring):
 *   G1  Time window (09:30–14:00)
 *   G2  15m EMA10 > EMA30 (structural trend)
 *   G3  15m ADX > threshold (trend is strong, not choppy) ← NEW
 *   G4  Optional VWAP gate
 *   G5  BB squeeze guard (await expansion)
 *
 * Confluence scoring — 4 quality factors (need min score):
 *   C1  EMA gap EXPANDING on 5m (momentum accelerating, not just aligned)
 *   C2  RSI in zone AND RISING vs 2 bars ago (direction confirmed)
 *   C3  MACD histogram positive AND EXPANDING (building, not fading)
 *   C4  Volume SPIKE on directional candle (institutional conviction) ← NEW
 *
 * Replacing old "price above BB midline" (statistically weak, ≈50% true always).
 */
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

        Candle    latest = candles5m.get(candles5m.size() - 1);
        LocalTime t      = latest.getTs().toLocalTime();
        if (t.isBefore(config.entryStart()) || t.isAfter(config.entryCutoff())) {
            return StrategySignal.neutral("outside_entry_window");
        }

        // ── G2 + G3: 15m trend gate + ADX strength gate ──────────────
        List<Candle> candles15m = TimeframeAggregator.toFifteenMinutes(candles5m);
        if (candles15m.size() < config.emaSlow() + 5) {
            return StrategySignal.neutral("insufficient_15m_candles");
        }

        BarSeriesCache cache15m  = BarSeriesCache.of(candles15m);
        double ema10_15m = cache15m.ema(config.emaFast());
        double ema30_15m = cache15m.ema(config.emaSlow());

        if (ema10_15m <= 0 || ema30_15m <= 0) {
            return StrategySignal.neutral("15m_emas_invalid");
        }

        // G3: ADX — only trade trending markets, ignore choppy sideways
        double adx15m = cache15m.adx(config.adxPeriod());
        if (adx15m <= 0 || adx15m < config.adxThreshold()) {
            return StrategySignal.neutral("15m_adx_weak:" + String.format("%.1f", adx15m));
        }

        // ── G4: Optional VWAP gate ────────────────────────────────────
        double  vwap      = config.useVwapGate() ? VwapIndicator.current(candles5m) : 0;
        double  close     = latest.getClose();
        boolean aboveVwap = !config.useVwapGate() || (vwap > 0 && close > vwap);
        boolean belowVwap = !config.useVwapGate() || (vwap > 0 && close < vwap);

        boolean trend15mBull = ema10_15m > ema30_15m && aboveVwap;
        boolean trend15mBear = ema10_15m < ema30_15m && belowVwap;

        if (!trend15mBull && !trend15mBear) {
            return StrategySignal.neutral("15m_trend_flat_or_vwap_mismatch");
        }

        // ── 5m series — build once ────────────────────────────────────
        BarSeriesCache cache5m = BarSeriesCache.of(candles5m);

        // ── G5: BB squeeze guard ──────────────────────────────────────
        BarSeriesCache.BollingerValues bb = cache5m.bollinger(config.bbPeriod(), config.bbK());
        if (bb.isValid() && bb.isSqueeze(close, config.bbSqueezeThreshold())) {
            return StrategySignal.neutral("bb_squeeze_awaiting_breakout");
        }

        // ── Confluence scoring ────────────────────────────────────────
        int bullScore = 0, bearScore = 0;
        StringJoiner bullR = new StringJoiner(",");
        StringJoiner bearR = new StringJoiner(",");

        // C1: EMA gap EXPANDING on 5m
        //     Not just "ema10 > ema30" but the spread is actively widening.
        //     Distinguishes fresh momentum from old stale crossovers.
        double ema10_5m     = cache5m.ema(config.emaFast());
        double ema30_5m     = cache5m.ema(config.emaSlow());
        double ema10_5mPrev = cache5m.emaAt(config.emaFast(), 2);
        double ema30_5mPrev = cache5m.emaAt(config.emaSlow(), 2);
        if (ema10_5m > 0 && ema30_5m > 0 && ema10_5mPrev > 0 && ema30_5mPrev > 0) {
            double gapNow  = ema10_5m - ema30_5m;
            double gapPrev = ema10_5mPrev - ema30_5mPrev;
            if (gapNow > 0 && gapNow > gapPrev) { bullScore++; bullR.add("EMA_EXPAND"); }
            else if (gapNow < 0 && gapNow < gapPrev) { bearScore++; bearR.add("EMA_EXPAND"); }
        }

        // C2: RSI in zone AND actively RISING (for bull) or FALLING (for bear)
        //     A flat RSI sitting at 65 has no edge. RSI rising from 55 to 63 = momentum building.
        double rsi     = cache5m.rsi(config.rsiPeriod());
        double rsiPrev = cache5m.rsiAt(config.rsiPeriod(), 2);
        if (rsi > 0 && rsiPrev > 0) {
            if (rsi > config.rsiBullLow() && rsi < config.rsiBullHigh() && rsi > rsiPrev) {
                bullScore++; bullR.add("RSI_RISING:" + String.format("%.1f", rsi));
            }
            if (rsi > config.rsiBearLow() && rsi < config.rsiBearHigh() && rsi < rsiPrev) {
                bearScore++; bearR.add("RSI_FALLING:" + String.format("%.1f", rsi));
            }
        }

        // C3: MACD histogram positive AND EXPANDING
        //     Expanding histogram = momentum accelerating. Shrinking histogram =
        //     momentum fading — do NOT enter even if still positive.
        double macdHist     = cache5m.macdHistogram(config.macdShort(), config.macdLong(), config.macdSignal());
        double macdHistPrev = cache5m.macdHistAt(config.macdShort(), config.macdLong(), config.macdSignal(), 1);
        if (macdHist != 0) {
            if (macdHist > 0 && macdHist > macdHistPrev) { bullScore++; bullR.add("MACD_EXPAND"); }
            if (macdHist < 0 && macdHist < macdHistPrev) { bearScore++; bearR.add("MACD_EXPAND"); }
        }

        // C4: Volume spike on a directional candle
        //     High volume on a green candle = institutions buying. This is the most
        //     reliable confirmation that the move has real participation behind it.
        double  volRatio  = cache5m.volumeRatio(config.volAvgPeriod());
        boolean greenBar  = latest.getClose() > latest.getOpen();
        boolean redBar    = latest.getClose() < latest.getOpen();
        if (volRatio >= config.volSpikeRatio()) {
            if (greenBar) { bullScore++; bullR.add("VOL_SPIKE:" + String.format("%.2f", volRatio) + "x"); }
            if (redBar)   { bearScore++; bearR.add("VOL_SPIKE:" + String.format("%.2f", volRatio) + "x"); }
        }

        if (log.isDebugEnabled()) {
            log.debug("MICS | 15m ema10:{} ema30:{} adx:{} bull:{} | 5m gap:{} rsi:{} macd:{} vol:{}x | scores bull:{} bear:{}",
                String.format("%.2f", ema10_15m), String.format("%.2f", ema30_15m),
                String.format("%.1f", adx15m), trend15mBull,
                String.format("%.2f", ema10_5m - ema30_5m),
                String.format("%.1f", rsi), String.format("%.5f", macdHist),
                String.format("%.2f", volRatio),
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
