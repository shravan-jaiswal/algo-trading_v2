# Algo Trading v2 — Operations Guide

**VPS IP:** `103.212.121.27`
**App directory:** `/opt/algo-trading_v2`
**Service name:** `algo-trading_v2`

---

## WHERE TO RUN COMMANDS

| Task | Run From |
|---|---|
| Deploy, upload files, SSH | **Local — PowerShell** |
| Deploy scripts (deploy.sh, backup-and-deploy.sh) | **Local — Git Bash** |
| Logs, start/stop, config edits | **VPS — after SSH in** |
| Telegram commands | **Telegram app (any device)** |
| Health check | **Browser** |

---

## 1. First-Time Setup (One Time Only)

### Step 1 — Local PowerShell
```powershell
# Upload setup files to VPS
scp deploy/setup-vps.sh deploy/algo-trading_v2.service root@103.212.121.27:~

# Run setup (installs Java 21, creates user, registers service)
ssh root@103.212.121.27 "bash setup-vps.sh"

# Upload production config template
scp deploy/application.properties.template root@103.212.121.27:/opt/algo-trading_v2/application.properties
```

### Step 2 — VPS (SSH in first)
```powershell
ssh root@103.212.121.27        # SSH in from PowerShell
```
```bash
# Edit config with real credentials
nano /opt/algo-trading_v2/application.properties
```
Fill in:
- `broker.client.id`, `broker.pin`, `broker.totp.secret`, `broker.api.key`
- `db.password`
- `telegram.bot.token`, `telegram.chat.id`
- `trading.mode=paper` (start with paper, switch to live when ready)
- `risk.capital=your_capital`

Save: `Ctrl+O` → `Enter` → `Ctrl+X`

### Step 3 — Create Database (VPS)
```bash
sudo -u postgres psql
```
```sql
CREATE ROLE algo WITH LOGIN PASSWORD 'your_password';
CREATE DATABASE algo_db_v2 OWNER algo;
\q
```

### Step 4 — Install Market-Hours Cron (VPS, one time)
```bash
crontab -e -u algo
```
Paste this (starts at 9 AM, stops at 3:45 PM, Mon–Fri):
```
0 9 * * 1-5  /bin/systemctl start algo-trading_v2
45 15 * * 1-5  /bin/systemctl stop algo-trading_v2
```

---

## 2. Deploy New Code

### Code change only — Git Bash (local)
```bash
cd /d/DEV/shravan_github/algo-trading_v2
bash deploy/deploy.sh root@103.212.121.27
```
Builds JAR → uploads → restarts service automatically.

### Code + sync local DB to VPS — Git Bash (local)
```bash
bash deploy/backup-and-deploy.sh root@103.212.121.27
```
Dumps local DB → builds JAR → uploads both → restores DB → restarts service.

---

## 3. Start / Stop / Restart

### From Local PowerShell
```powershell
# Start
ssh root@103.212.121.27 "systemctl start algo-trading_v2"

# Stop
ssh root@103.212.121.27 "systemctl stop algo-trading_v2"

# Restart
ssh root@103.212.121.27 "systemctl restart algo-trading_v2"
```

### From VPS terminal
```bash
systemctl start algo-trading_v2
systemctl stop algo-trading_v2
systemctl restart algo-trading_v2
```

---

## 4. Check Status

### From Local PowerShell
```powershell
ssh root@103.212.121.27 "systemctl status algo-trading_v2 --no-pager"
```

### From Browser
```
http://103.212.121.27:8080/health
http://103.212.121.27:8080/status
http://103.212.121.27:8080/replay/today?strategy=MICS
```

### From VPS terminal
```bash
systemctl status algo-trading_v2 --no-pager
curl http://localhost:8080/health
curl "http://localhost:8080/replay/today?strategy=MICS"
```

### What `/status` Shows
`/status` includes the normal risk snapshot plus runtime diagnostics:
- latest candle timestamp, close, volume, and LTP per watchlist token
- last strategy evaluation per token/strategy
- no-signal reason such as `insufficient_candles`, `outside_entry_window`, `adx_weak`, or `no_confluence`
- open positions with current tracked LTP

---

## 5. Replay Today's Candles and Signal Diagnostics

Use this when signals are not firing and you want to know exactly why.

### Replay API From Browser
```
http://103.212.121.27:8080/replay/today?strategy=MICS
http://103.212.121.27:8080/replay/today?strategy=VSRSI
http://103.212.121.27:8080/replay/today?strategy=MICS&token=1333
http://103.212.121.27:8080/replay/today?strategy=MICS&timeframe=FIVE_MINUTE
```

### Replay API From VPS
```bash
curl "http://localhost:8080/replay/today?strategy=MICS"
curl "http://localhost:8080/replay/today?strategy=MICS&token=1333"
```

### Example API Output
```json
{
  "strategy": "MICS",
  "timeframe": "FIVE_MINUTE",
  "symbolCount": 4,
  "totalSignals": 1,
  "symbols": [
    {
      "symbol": "RELIANCE-EQ",
      "token": "1333",
      "candles": 48,
      "signalCount": 1,
      "lastSignal": "LONG",
      "reason": "signal_long",
      "lastCandleTs": "2026-05-19T14:10",
      "signals": [
        {
          "symbol": "RELIANCE-EQ",
          "strategy": "MICS",
          "signal": "LONG",
          "price": 2938.4,
          "stopLoss": 2919.2
        }
      ]
    }
  ]
}
```

### Replay CLI From VPS
```bash
cd /opt/algo-trading_v2
java -jar algo-trading-v2-2.0.0.jar --replay-today -s MICS
java -jar algo-trading-v2-2.0.0.jar --replay-today -s VSRSI
java -jar algo-trading-v2-2.0.0.jar --replay-today -s MICS -t 1333
```

Example log output:
```text
Replay SIGNAL | RELIANCE-EQ | MICS LONG @ Rs.2938.40 | sl=Rs.2919.20
Replay summary | RELIANCE-EQ | candles=48 signals=1 lastSignal=LONG reason=signal_long
Replay summary | TCS-EQ | candles=48 signals=0 lastSignal=NONE reason=adx_weak:18.4
Replay complete | strategy=MICS symbols=4 totalSignals=1
```

Strategy signal names:
| Signal | Meaning |
|---|---|
| `LONG` | Enter or stay long |
| `LONG_EXIT` | Exit long only |
| `SHORT` | Enter or stay short |
| `SHORT_EXIT` | Exit short only |
| `NONE` | No actionable signal |

### Strategy Trade Type
Each strategy can be restricted to long-only, short-only, or both:
```properties
strategy.vsrsi.trade.type=BOTH
strategy.mics.trade.type=BOTH
strategy.ma.trade.type=BOTH
strategy.rsi.trade.type=BOTH
```

Allowed values:
| Value | Behavior |
|---|---|
| `LONG` | Allows `LONG`; blocks `SHORT` signals |
| `SHORT` | Allows `SHORT`; blocks `LONG` signals |
| `BOTH` | Allows both directions |

Blocked signals are written to `strategy_signal_audit` as:
```text
trade_type_blocked:LONG:SHORT
```

### Strategy Holding Type
Each strategy can also be set to intraday or delivery/positional holding:
```properties
strategy.vsrsi.holding.type=INTRADAY
strategy.mics.holding.type=INTRADAY
strategy.ma.holding.type=INTRADAY
strategy.rsi.holding.type=INTRADAY
```

Allowed values:
| Value | Behavior |
|---|---|
| `INTRADAY` | Backtest closes on/after `market.squareoff`; live engine square-offs at `market.squareoff` |
| `DELIVERY` | Backtest can carry overnight; live square-off skips the position |

`INTRADY` is accepted as `INTRADAY`, and `DELEVERY` is accepted as `DELIVERY`, to avoid config typo failures.

Live broker orders also use holding-aware product types:
```properties
order.product.type.intraday=INTRADAY
order.product.type.delivery.equity=DELIVERY
order.product.type.delivery.derivatives=NRML
```

Entry, exit, and stop-loss orders use the same product type for the tracked position. If your Angel One account expects a different carry-forward product for F&O, set `order.product.type.delivery.derivatives` accordingly on the VPS.

### VSRSI Supertrend Mode
VSRSI supports two Supertrend modes:
```properties
strategy.vsrsi.supertrend.mode=LEGACY
strategy.vsrsi.supertrend.mode=REAL
```

Use `LEGACY` to keep the loose, non-trailing Supertrend proxy with ATR-band stop placement.
Use `REAL` only when testing the corrected trailing Supertrend calculation in replay/backtest, because it changes entry timing and stop placement.

Important for shorts: updated `LEGACY` mode uses candle direction for the Supertrend trend proxy, so bearish candles below VWAP with bear-zone RSI can produce `SHORT` signals while still using legacy ATR bands for stop loss.

After changing it on the VPS:
```bash
systemctl restart algo-trading_v2
curl "http://localhost:8080/replay/today?strategy=VSRSI"
```

For a one-time backtest without editing the VPS config:
```bash
java -Dstrategy.vsrsi.supertrend.mode=REAL -cp /opt/algo-trading_v2/algo-trading-v2-2.0.0.jar com.trading.backtest.BacktestRunner ALL FIVE_MINUTE VSRSI 60
```

On newer builds you can also pass the mode as the fifth argument:
```bash
java -cp /opt/algo-trading_v2/algo-trading-v2-2.0.0.jar com.trading.backtest.BacktestRunner ALL FIVE_MINUTE VSRSI 60 REAL
```

You can force holding type as the sixth argument:
```bash
java -cp /opt/algo-trading_v2/algo-trading-v2-2.0.0.jar com.trading.backtest.BacktestRunner ALL FIVE_MINUTE VSRSI 60 LEGACY DELIVERY
java -cp /opt/algo-trading_v2/algo-trading-v2-2.0.0.jar com.trading.backtest.BacktestRunner ALL FIVE_MINUTE VSRSI 60 LEGACY INTRADAY
```

The backtest prints `VSRSI config`, `VSRSI gates`, and `VSRSI short gates`. If `rawShort=0`, the strategy conditions did not produce any short signal. If `rawShort` is above zero but `Total Short Trades` is zero, the backtest entry/position handling needs review.
Trade rows include `reason=SL`, `reason=TSL`, `reason=SIGNAL`, `reason=TP`, `reason=INTRADAY_EXIT`, `reason=EOD_FALLBACK`, or `reason=FINAL_BAR`.
Live closed rows in `executed_trades` store the same reason in `exit_reason`; manual `/closeall` stores `MANUAL_EXIT`.

### Signal Audit Table
The app creates and writes to:
```sql
public.strategy_signal_audit
```

Latest strategy evaluations:
```bash
sudo -u postgres psql -d algo_db_v2
```
```sql
SELECT evaluated_at, token, symbol, strategy_name, candle_ts,
       signal, reason, close, rsi, vwap, supertrend,
       adx, volume_ratio, bull_score, bear_score
FROM public.strategy_signal_audit
ORDER BY evaluated_at DESC
LIMIT 50;
```

Check why one token is not firing:
```sql
SELECT candle_ts, strategy_name, signal, reason,
       close, rsi, vwap, adx, volume_ratio,
       bull_score, bear_score
FROM public.strategy_signal_audit
WHERE token = '1333'
ORDER BY evaluated_at DESC
LIMIT 30;
```

Check all entry/exit signals today:
```sql
SELECT evaluated_at, symbol, token, strategy_name, candle_ts,
       signal, close, reason
FROM public.strategy_signal_audit
WHERE signal IN ('LONG', 'LONG_EXIT', 'SHORT', 'SHORT_EXIT')
  AND evaluated_at::date = CURRENT_DATE
ORDER BY evaluated_at DESC;
```

---

## 6. View Logs

### SSH in first
```powershell
ssh root@103.212.121.27
```

### Live logs (cleanest — like old screen session)
```bash
journalctl -u algo-trading_v2 -f -o cat
#or
ssh root@103.212.121.27 "journalctl -u algo-trading_v2 -f -o cat"
```

### Live logs from log file
```bash
tail -f /opt/algo-trading_v2/logs/algo-trading_v2.log
```

### Last 100 lines
```bash
journalctl -u algo-trading_v2 -n 100 -o cat
```

### Today's logs only
```bash
journalctl -u algo-trading_v2 --since today -o cat
```

### Search logs
```bash
# All trades
grep "BUY\|SELL\|CLOSE\|P&L" /opt/algo-trading_v2/logs/algo-trading_v2.log

# Errors only
grep "ERROR\|WARN" /opt/algo-trading_v2/logs/algo-trading_v2.log
```

### Exit live log view
```
Ctrl+C
```

---

## 7. Switch Paper ↔ Live Mode

### SSH into VPS
```powershell
ssh root@103.212.121.27
```
```bash
nano /opt/algo-trading_v2/application.properties
```
Change:
```properties
trading.mode=paper     # safe testing
trading.mode=live      # real money
```
Save → `Ctrl+O`, `Enter`, `Ctrl+X`

```bash
systemctl restart algo-trading_v2
```

---

## 8. Telegram Commands

Send these from **Telegram app** to your bot:

| Command | What it does |
|---|---|
| `/help` | List all commands |
| `/status` | Mode, open positions, capital, halted |
| `/pnl` | Today's profit / loss / net |
| `/positions` | All open positions with entry price |
| `/halt` | Stop new trades immediately |
| `/resume` | Resume trading after halt |
| `/closeall` | Emergency — close all open positions |

---

## 9. Edit Config on VPS

```powershell
ssh root@103.212.121.27        # from PowerShell
```
```bash
nano /opt/algo-trading_v2/application.properties
# make changes
# Ctrl+O to save, Ctrl+X to exit
systemctl restart algo-trading_v2
```

---

## 10. Troubleshooting

### App not starting
```bash
journalctl -u algo-trading_v2 -n 50 -o cat
# Look for ERROR lines
```

### Two Java processes running (old + new conflict)
```bash
ps aux | grep java
kill -9 <OLD_PID>
```

### Check Java is running
```bash
ps aux | grep java
```

### Check memory / CPU
```bash
top -p $(pgrep java)
```

### Database issue
```bash
sudo -u postgres psql -c "\l"                    # list databases
sudo -u postgres psql -d algo_db_v2 -c "\dt"    # list tables
```

### Candle data is behind current 5-minute slot
```sql
SELECT token, timeframe, ts, open, high, low, close, volume
FROM public.candles
ORDER BY token ASC, timeframe ASC, ts DESC
LIMIT 30;
```
Expected behavior:
- at `14:10`, the `14:10` row should appear after the first tick in that slot
- at `14:13`, the `14:10` row is still in progress and may keep updating
- at `14:15`, the `14:10` candle is complete and signal evaluation should happen
- after `market.close`, no new regular candle slots should be created

If volume is `0`, confirm SmartStream quote mode is enabled:
```bash
grep "feed.mode" /opt/algo-trading_v2/application.properties
```
Expected:
```properties
feed.mode=QUOTE
```

Remove accidental after-market candle rows:
```sql
DELETE FROM public.candles
WHERE ts::time >= TIME '15:30'
   OR ts::time <  TIME '09:15';
```

### Signals are not firing
Check the replay API:
```bash
curl "http://localhost:8080/replay/today?strategy=MICS"
```

Check audit reasons:
```sql
SELECT evaluated_at, symbol, token, strategy_name, candle_ts,
       signal, reason, rsi, adx, volume_ratio, bull_score, bear_score
FROM public.strategy_signal_audit
ORDER BY evaluated_at DESC
LIMIT 50;
```

Common reasons:
| Reason | Meaning |
|---|---|
| `insufficient_candles` | Not enough history for the strategy |
| `outside_entry_window` | Candle is outside configured entry time |
| `15m_adx_weak` | MICS trend strength gate failed |
| `15m_trend_flat_or_vwap_mismatch` | 15m EMA/VWAP direction gate failed |
| `bb_squeeze_awaiting_breakout` | Market is compressed; strategy waits |
| `no_confluence:bull=x,bear=y` | Score did not reach `strategy.mics.min.confluence.score` |
| `signal_long` / `signal_short` | Strategy generated an entry signal |

### Service stuck - force kill and restart
```bash
systemctl kill algo-trading_v2
systemctl start algo-trading_v2
```

### View old log files (rotated daily, compressed)
```bash
ls /opt/algo-trading_v2/logs/
zcat /opt/algo-trading_v2/logs/algo-trading_v2.2026-05-13.log.gz
```

---

## 11. File Locations

| File | Location |
|---|---|
| JAR | `/opt/algo-trading_v2/algo-trading-v2-2.0.0.jar` |
| Config | `/opt/algo-trading_v2/application.properties` |
| Live log | `/opt/algo-trading_v2/logs/algo-trading_v2.log` |
| Systemd service | `/etc/systemd/system/algo-trading_v2.service` |
| DB backups (local) | `deploy/backups/` |
| Signal audit table | `public.strategy_signal_audit` |

---

## 12. Quick Reference Card

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FROM LOCAL POWERSHELL
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SSH IN          → ssh root@103.212.121.27
CHECK STATUS    → ssh root@103.212.121.27 "systemctl status algo-trading_v2 --no-pager"
START           → ssh root@103.212.121.27 "systemctl start algo-trading_v2"
STOP            → ssh root@103.212.121.27 "systemctl stop algo-trading_v2"
RESTART         → ssh root@103.212.121.27 "systemctl restart algo-trading_v2"
LIVE LOGS       → ssh root@103.212.121.27 "journalctl -u algo-trading_v2 -f -o cat"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FROM LOCAL GIT BASH  (cd /d/DEV/shravan_github/algo-trading_v2)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEPLOY CODE     → bash deploy/deploy.sh root@103.212.121.27
DEPLOY + DB     → bash deploy/backup-and-deploy.sh root@103.212.121.27

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FROM BROWSER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
HEALTH CHECK    → http://103.212.121.27:8080/health
STATUS          -> http://103.212.121.27:8080/status
REPLAY MICS     -> http://103.212.121.27:8080/replay/today?strategy=MICS
REPLAY TOKEN    -> http://103.212.121.27:8080/replay/today?strategy=MICS&token=1333

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FROM TELEGRAM BOT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
/help  /status  /pnl  /positions  /halt  /resume  /closeall
```
