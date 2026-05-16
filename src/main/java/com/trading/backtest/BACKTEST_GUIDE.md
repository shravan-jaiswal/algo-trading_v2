# Algo Trading v2 — Backtest Guide

## Overview

The backtest engine replays historical candle data bar-by-bar through a strategy and tracks P&L,
win rate, drawdown, and profit factor. It uses the same strategy code as live trading — no
separate backtest implementation.

**Key classes:**
- `BacktestEngine.java` — core event-driven replay loop
- `BacktestRunner.java` — CLI entry point (single symbol or full watchlist)

---

## Prerequisites

1. The database must have candle history. Run the app in live/paper mode first to populate it,
   or use the API backfill on startup.
2. The watchlist table must be populated (for `runAll` mode).
3. `application.properties` must be configured (capital, risk settings are read by RiskConfig).

---

## How to Run (PowerShell)

> Use **single quotes** around `-D` flags in PowerShell to prevent argument mangling.

### Single Symbol

```powershell
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=<token> <timeframe> <strategy> <days>'
```

**Arguments:**

| Argument    | Description                                    | Example        |
|-------------|------------------------------------------------|----------------|
| `token`     | Angel One instrument token                     | `99926000`     |
| `timeframe` | DB timeframe string                            | `FIVE_MINUTE`  |
| `strategy`  | Strategy name: `MICS`, `MA`, `VSRSI`           | `MICS`         |
| `days`      | Number of historical days to test              | `60`           |

**Examples:**

```powershell
# NIFTY 50 — MICS strategy — last 60 days
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=2885 FIVE_MINUTE MICS 60'

# RELIANCE — MA Crossover — last 30 days
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=2885 FIVE_MINUTE MA 30'

# TCS — VSRSI — last 90 days
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=11536 FIVE_MINUTE VSRSI 90'
```

---

### All Watchlist Symbols

```powershell
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=ALL FIVE_MINUTE MICS 60'
```

Runs the strategy on every active symbol in the watchlist and prints a portfolio summary at the end.

---

## Output — Per Symbol

```
-- Backtest Results ------------------
  Trades        : 42
  Winners       : 28 (66.7%)
  Losers        : 14
  Total P&L     : Rs.+84230.00
  Max Drawdown  : Rs.12500.00
  Avg Win       : Rs.4800.00
  Avg Loss      : Rs.2300.00
  Profit Factor : 2.08
--------------------------------------
```

| Metric         | Meaning                                                    |
|----------------|------------------------------------------------------------|
| Trades         | Total completed trades                                     |
| Winners        | Trades with positive P&L                                   |
| Losers         | Trades with negative P&L                                   |
| Total P&L      | Sum of all trade P&L in Rs.                               |
| Max Drawdown   | Largest peak-to-trough equity drop in Rs.                 |
| Avg Win        | Average Rs. profit on winning trades                       |
| Avg Loss       | Average Rs. loss on losing trades                          |
| Profit Factor  | Gross profit / Gross loss — above 1.5 is considered good  |

---

## Output — Portfolio Summary

```
========== PORTFOLIO SUMMARY (60 days) ==========
  Symbols tested      : 51
  Profitable symbols  : 38 / 51
  Total Trades        : 1820
  Total Winners       : 1192 (65.5%)
  Total Losers        : 628
  Total P&L           : Rs.+342000.00
  Avg P&L per Symbol  : Rs.+6706.00
  Total Drawdown      : Rs.180000.00
  Portfolio PF        : 2.14
==================================================
```

---

## Strategies Available

| Code    | Class                              | Description                                  |
|---------|------------------------------------|----------------------------------------------|
| `MICS`  | `MultiIndicatorConfluenceStrategy` | 15m trend + 5m confluence (EMA/RSI/MACD/BB) |
| `MA`    | `MACrossoverStrategy`              | EMA(10) / EMA(30) crossover                  |
| `VSRSI` | `VwapSupertrendRsiStrategy`        | VWAP + Supertrend + RSI confirmation         |

---

## Key Configuration (application.properties)

These settings are used during backtest exactly as in live trading:

```properties
risk.capital=1200000           # Starting capital for P&L tracking
risk.per.trade=0.01            # Risk 1% of capital per trade
risk.reward.ratio=3.0          # Take profit = 3x the stop distance
risk.tsl.trail.pct=0.01        # Trailing stop loss (1%)

strategy.mics.entry.start=09:30
strategy.mics.entry.cutoff=14:00
strategy.mics.min.confluence.score=3
strategy.mics.use.vwap.gate=false    # Recommended: false for clean backtests
```

---

## Common Token Reference (NSE)

| Symbol    | Token      |
|-----------|------------|
| NIFTY 50  | `99926000` |
| RELIANCE  | `2885`     |
| TCS       | `11536`    |
| INFY      | `1594`     |
| SBIN      | `3045`     |
| KOTAKBANK | `1922`     |
| HDFCBANK  | `1333`     |
| ICICIBANK | `4963`     |
| TATASTEEL | `3499`     |
| WIPRO     | `3787`     |

> Full token list is in the `watchlist` table in the database.

---

## Backtest Limitations

- **No slippage** — entry/exit at bar close price, no fill delay
- **No option premium modelling** — for `OPTION_BUY` mode the underlying price is used
- **No intraday time-stop** — square-off at 15:15 is not simulated (use `entry.cutoff=14:00`)
- **Survivorship bias** — only tests symbols currently in the watchlist
- **Single position** — one open trade at a time per symbol (no pyramiding)

---

## Adding a New Strategy to Backtest

1. Implement the `Strategy` interface in `src/main/java/com/trading/strategy/`
2. Add a `case` for it in `BacktestRunner.buildStrategy()`
3. Run:

```powershell
mvn exec:java '-Dexec.mainClass=com.trading.backtest.BacktestRunner' '-Dexec.args=<token> FIVE_MINUTE <YOURCODE> 60'
```
