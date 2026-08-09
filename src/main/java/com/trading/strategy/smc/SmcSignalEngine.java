package com.trading.strategy.smc;

import com.trading.indicator.BarSeriesCache;
import com.trading.indicator.TimeframeAggregator;
import com.trading.indicator.VwapIndicator;
import com.trading.model.Candle;
import com.trading.strategy.Strategy.Signal;

import java.time.LocalTime;
import java.util.List;

public final class SmcSignalEngine {

    public record Result(Signal signal, double stopLoss, String reason) {
        static Result none(String reason) { return new Result(Signal.NONE, -1, reason); }
    }

    private final SmcConfig cfg;

    public SmcSignalEngine(SmcConfig cfg) {
        this.cfg = cfg;
    }

    public Result evaluate(List<Candle> candles) {
        if (candles == null || candles.size() < minRequiredCandles()) return Result.none("insufficient_candles");
        Candle latest = candles.get(candles.size() - 1);
        LocalTime time = latest.getTs().toLocalTime();
        if (time.isBefore(cfg.entryStart()) || time.isAfter(cfg.entryCutoff())) return Result.none("outside_entry_window");

        BarSeriesCache cache = BarSeriesCache.of(candles);
        double atr = cache.atr(cfg.atrPeriod());
        if (atr <= 0) return Result.none("atr_invalid");
        if (cache.volumeRatio(cfg.volumeAvgPeriod()) < cfg.volumeSpikeRatio()) return Result.none("volume_too_low");

        int from = Math.max(1, candles.size() - 1 - cfg.sweepRecentBars());
        Sweep bull = null, bear = null;
        for (int i = from; i < candles.size(); i++) {
            Sweep candidateBull = bullishSweep(candles, i, cfg.liquidityLookback());
            Sweep candidateBear = bearishSweep(candles, i, cfg.liquidityLookback());
            if (candidateBull != null) bull = candidateBull;
            if (candidateBear != null) bear = candidateBear;
        }

        double close = latest.getClose();
        double vwap = VwapIndicator.current(candles);
        Bias bias5m = bias(TimeframeAggregator.toFiveMinutes(candles));
        boolean bullFvg = hasRecentBullishFvg(candles, atr, cfg.minFvgAtrPct(), cfg.sweepRecentBars());
        boolean bearFvg = hasRecentBearishFvg(candles, atr, cfg.minFvgAtrPct(), cfg.sweepRecentBars());

        if (bull != null && bullishStructureShift(candles, bull.index(), cfg.swingLookback())
                && close > vwap && bias5m != Bias.BEAR && bullFvg) {
            return new Result(Signal.LONG, Math.min(bull.level(), close - atr * cfg.atrStopMultiplier()),
                    "bullish_sweep_mss_vwap");
        }
        if (bear != null && bearishStructureShift(candles, bear.index(), cfg.swingLookback())
                && close < vwap && bias5m != Bias.BULL && bearFvg) {
            return new Result(Signal.SHORT, Math.max(bear.level(), close + atr * cfg.atrStopMultiplier()),
                    "bearish_sweep_mss_vwap");
        }
        return Result.none("no_smc_setup");
    }

    public int minRequiredCandles() {
        return Math.max(cfg.volumeAvgPeriod() + 1, cfg.liquidityLookback() + cfg.sweepRecentBars() + 2);
    }

    public static Sweep bullishSweep(List<Candle> candles, int index, int lookback) {
        if (index <= 0 || index >= candles.size()) return null;
        double level = priorLow(candles, index, lookback);
        Candle candle = candles.get(index);
        return candle.getLow() < level && candle.getClose() > level ? new Sweep(index, level) : null;
    }

    public static Sweep bearishSweep(List<Candle> candles, int index, int lookback) {
        if (index <= 0 || index >= candles.size()) return null;
        double level = priorHigh(candles, index, lookback);
        Candle candle = candles.get(index);
        return candle.getHigh() > level && candle.getClose() < level ? new Sweep(index, level) : null;
    }

    public static boolean hasEqualLows(List<Candle> candles, int lookback, double tolerance) {
        return hasEqualLevels(candles, lookback, tolerance, true);
    }

    public static boolean hasEqualHighs(List<Candle> candles, int lookback, double tolerance) {
        return hasEqualLevels(candles, lookback, tolerance, false);
    }

    public static boolean bullishFvg(List<Candle> candles, int index, double atr, double minAtrPct) {
        return index >= 2 && candles.get(index).getLow() - candles.get(index - 2).getHigh() >= atr * minAtrPct;
    }

    public static boolean bearishFvg(List<Candle> candles, int index, double atr, double minAtrPct) {
        return index >= 2 && candles.get(index - 2).getLow() - candles.get(index).getHigh() >= atr * minAtrPct;
    }

    public static boolean bullishStructureShift(List<Candle> candles, int sweepIndex, int swingLookback) {
        if (sweepIndex < 1 || candles.isEmpty()) return false;
        return candles.get(candles.size() - 1).getClose() > priorHigh(candles, sweepIndex, swingLookback);
    }

    public static boolean bearishStructureShift(List<Candle> candles, int sweepIndex, int swingLookback) {
        if (sweepIndex < 1 || candles.isEmpty()) return false;
        return candles.get(candles.size() - 1).getClose() < priorLow(candles, sweepIndex, swingLookback);
    }

    public record Sweep(int index, double level) {}
    private enum Bias { BULL, BEAR, FLAT }

    private static Bias bias(List<Candle> candles) {
        if (candles.size() < 10) return Bias.FLAT;
        BarSeriesCache cache = BarSeriesCache.of(candles);
        double fast = cache.ema(5), slow = cache.ema(9);
        return fast > slow ? Bias.BULL : fast < slow ? Bias.BEAR : Bias.FLAT;
    }

    private static boolean hasRecentBullishFvg(List<Candle> candles, double atr, double pct, int bars) {
        for (int i = Math.max(2, candles.size() - bars); i < candles.size(); i++)
            if (bullishFvg(candles, i, atr, pct)) return true;
        return false;
    }

    private static boolean hasRecentBearishFvg(List<Candle> candles, double atr, double pct, int bars) {
        for (int i = Math.max(2, candles.size() - bars); i < candles.size(); i++)
            if (bearishFvg(candles, i, atr, pct)) return true;
        return false;
    }

    private static boolean hasEqualLevels(List<Candle> candles, int lookback, double tolerance, boolean lows) {
        int start = Math.max(0, candles.size() - lookback);
        for (int i = start; i < candles.size(); i++) {
            double first = lows ? candles.get(i).getLow() : candles.get(i).getHigh();
            for (int j = i + 1; j < candles.size(); j++) {
                double second = lows ? candles.get(j).getLow() : candles.get(j).getHigh();
                if (Math.abs(first - second) <= tolerance) return true;
            }
        }
        return false;
    }

    private static double priorHigh(List<Candle> candles, int endExclusive, int lookback) {
        int start = Math.max(0, endExclusive - lookback);
        return candles.subList(start, endExclusive).stream().mapToDouble(Candle::getHigh).max().orElse(Double.MAX_VALUE);
    }

    private static double priorLow(List<Candle> candles, int endExclusive, int lookback) {
        int start = Math.max(0, endExclusive - lookback);
        return candles.subList(start, endExclusive).stream().mapToDouble(Candle::getLow).min().orElse(-Double.MAX_VALUE);
    }
}
