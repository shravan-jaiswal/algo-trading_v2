package com.trading.strategy.scalping;

import com.trading.indicator.BarSeriesCache;
import com.trading.indicator.TimeframeAggregator;
import com.trading.indicator.VwapIndicator;
import com.trading.model.Candle;
import com.trading.strategy.Strategy.Signal;
import com.trading.utils.MarketUtils;

import java.time.LocalTime;
import java.util.List;

public final class MomentumScalpingSignalEngine {

    public record Result(Signal signal, double stopLoss, String reason) {
        static Result none(String reason) { return new Result(Signal.NONE, -1, reason); }
    }

    private final MomentumScalpingConfig cfg;

    public MomentumScalpingSignalEngine(MomentumScalpingConfig cfg) {
        this.cfg = cfg;
    }

    public Result evaluate(List<Candle> candles1m) {
        if (candles1m == null || candles1m.size() < minRequiredCandles()) return Result.none("insufficient_candles");
        Candle latest = candles1m.get(candles1m.size() - 1);
        LocalTime time = latest.getTs().toLocalTime();
        if (time.isBefore(cfg.entryStart()) || time.isAfter(cfg.entryCutoff())) return Result.none("outside_entry_window");

        BarSeriesCache cache = BarSeriesCache.of(candles1m);
        double close = latest.getClose();
        double atr = cache.atr(cfg.atrPeriod());
        if (atr <= 0 || atr / close < cfg.minAtrPct()) return Result.none("atr_too_low");
        double vwap = VwapIndicator.current(candles1m);
        double volumeRatio = cache.volumeRatio(cfg.volumeAvgPeriod());
        if (volumeRatio < cfg.volumeSpikeRatio()) return Result.none("volume_too_low");

        double emaFast = cache.ema(cfg.emaFast());
        double emaSlow = cache.ema(cfg.emaSlow());
        double rsi = cache.rsi(cfg.rsiPeriod());
        double macd = cache.macdHistogram(cfg.macdFast(), cfg.macdSlow(), cfg.macdSignal());
        Bias bias5m = bias(TimeframeAggregator.toFiveMinutes(candles1m));
        Bias bias15m = bias(TimeframeAggregator.toFifteenMinutes(TimeframeAggregator.toFiveMinutes(candles1m)));
        boolean breakoutHigh = close > openingRangeHigh(candles1m) || close > recentHigh(candles1m, cfg.swingLookback());
        boolean breakoutLow = close < openingRangeLow(candles1m) || close < recentLow(candles1m, cfg.swingLookback());

        boolean bull15m = !cfg.require15mBias() || bias15m == Bias.BULL;
        boolean bear15m = !cfg.require15mBias() || bias15m == Bias.BEAR;
        if (close > vwap && emaFast > emaSlow && rsi > cfg.rsiBull() && macd > 0
                && latest.isBullish() && breakoutHigh && bias5m == Bias.BULL && bull15m) {
            return new Result(Signal.LONG, close - atr * cfg.atrStopMultiplier(), "bullish_breakout_confluence");
        }
        if (close < vwap && emaFast < emaSlow && rsi < cfg.rsiBear() && macd < 0
                && latest.isBearish() && breakoutLow && bias5m == Bias.BEAR && bear15m) {
            return new Result(Signal.SHORT, close + atr * cfg.atrStopMultiplier(), "bearish_breakdown_confluence");
        }
        return Result.none("no_confluence");
    }

    public int minRequiredCandles() {
        return Math.max(cfg.macdSlow() + cfg.macdSignal() + 1,
                Math.max(cfg.volumeAvgPeriod() + 1, cfg.emaSlow() + 1));
    }

    private enum Bias { BULL, BEAR, FLAT }

    private static Bias bias(List<Candle> candles) {
        if (candles.size() < 10) return Bias.FLAT;
        BarSeriesCache cache = BarSeriesCache.of(candles);
        double fast = cache.ema(5), slow = cache.ema(9);
        return fast > slow ? Bias.BULL : fast < slow ? Bias.BEAR : Bias.FLAT;
    }

    private double openingRangeHigh(List<Candle> candles) {
        return sessionOpeningRange(candles).stream().mapToDouble(Candle::getHigh).max().orElse(Double.MAX_VALUE);
    }

    private double openingRangeLow(List<Candle> candles) {
        return sessionOpeningRange(candles).stream().mapToDouble(Candle::getLow).min().orElse(-Double.MAX_VALUE);
    }

    private List<Candle> sessionOpeningRange(List<Candle> candles) {
        var date = candles.get(candles.size() - 1).getTs().toLocalDate();
        var end = MarketUtils.marketOpenTime().plusMinutes(cfg.openingRangeMinutes());
        return candles.stream().filter(c -> c.getTs().toLocalDate().equals(date))
                .filter(c -> !c.getTs().toLocalTime().isBefore(MarketUtils.marketOpenTime()))
                .filter(c -> c.getTs().toLocalTime().isBefore(end)).toList();
    }

    private static double recentHigh(List<Candle> candles, int lookback) {
        int end = candles.size() - 1, start = Math.max(0, end - lookback);
        return candles.subList(start, end).stream().mapToDouble(Candle::getHigh).max().orElse(Double.MAX_VALUE);
    }

    private static double recentLow(List<Candle> candles, int lookback) {
        int end = candles.size() - 1, start = Math.max(0, end - lookback);
        return candles.subList(start, end).stream().mapToDouble(Candle::getLow).min().orElse(-Double.MAX_VALUE);
    }
}
