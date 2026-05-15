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
```

### From VPS terminal
```bash
systemctl status algo-trading_v2 --no-pager
curl http://localhost:8080/health
```

---

## 5. View Logs

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

## 6. Switch Paper ↔ Live Mode

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

## 7. Telegram Commands

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

## 8. Edit Config on VPS

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

## 9. Troubleshooting

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

### Service stuck — force kill and restart
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

## 10. File Locations

| File | Location |
|---|---|
| JAR | `/opt/algo-trading_v2/algo-trading-v2-2.0.0.jar` |
| Config | `/opt/algo-trading_v2/application.properties` |
| Live log | `/opt/algo-trading_v2/logs/algo-trading_v2.log` |
| Systemd service | `/etc/systemd/system/algo-trading_v2.service` |
| DB backups (local) | `deploy/backups/` |

---

## 11. Quick Reference Card

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

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FROM TELEGRAM BOT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
/help  /status  /pnl  /positions  /halt  /resume  /closeall
```

## W