#!/usr/bin/env bash
# setup-vps.sh — one-time server setup
# Run as root on Ubuntu 22.04 / 24.04 VPS
# Usage: bash setup-vps.sh
# Note: PostgreSQL and database must already exist. Create the DB manually before running this.

set -euo pipefail

APP_USER="algo"
APP_DIR="/opt/algo-trading_v2"
JAVA_DIR="/opt/java"
JAVA_VERSION="21.0.5+11"          # Update to latest Temurin 21 build if needed

echo "==> [1/5] System packages"
apt-get update -qq
apt-get install -y --no-install-recommends \
    wget curl tar unzip ca-certificates \
    ufw

echo "==> [2/5] Install Temurin 21 (Eclipse Adoptium)"
TEMURIN_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JAVA_VERSION/+/%2B}/OpenJDK21U-jdk_x64_linux_hotspot_${JAVA_VERSION/+/_}.tar.gz"
wget -q -O /tmp/jdk21.tar.gz "$TEMURIN_URL"
mkdir -p "$JAVA_DIR"
tar -xzf /tmp/jdk21.tar.gz -C "$JAVA_DIR" --strip-components=1
rm /tmp/jdk21.tar.gz
"$JAVA_DIR/bin/java" -version

echo "==> [3/5] Create system user and app directory"
id -u "$APP_USER" &>/dev/null || useradd --system --shell /bin/false --home-dir "$APP_DIR" "$APP_USER"
mkdir -p "$APP_DIR/logs"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

echo "==> [4/5] Install systemd service"
cp "$(dirname "$0")/algo-trading_v2.service" /etc/systemd/system/algo-trading_v2.service
sed -i "s|/opt/java/bin/java|$JAVA_DIR/bin/java|" /etc/systemd/system/algo-trading_v2.service
systemctl daemon-reload
systemctl enable algo-trading_v2
echo "  Service installed (not started — config first)"

echo "==> [5/5] Firewall"
ufw --force enable
ufw allow ssh
ufw allow 8080/tcp comment "algo-trading health check"
ufw status verbose

echo ""
cat <<INFO
========================================================
  NEXT STEPS
========================================================
1. Copy your production config:
     scp deploy/application.properties.template root@<VPS>:/opt/algo-trading_v2/application.properties
   Then edit /opt/algo-trading_v2/application.properties on the VPS and fill in:
     - broker.client.id / broker.pin / broker.totp.secret / broker.api.key
     - db.url / db.username / db.password
     - telegram.bot.token / telegram.chat.id
     - trading.mode=live
     - risk.capital=<your actual capital>

2. Copy the JAR:
     scp target/algo-trading-v2-2.0.0.jar root@<VPS>:/opt/algo-trading_v2/

3. Start the service:
     systemctl start algo-trading_v2
     journalctl -u algo-trading_v2 -f

4. (Optional) Set up market-hours cron:
     crontab -e -u $APP_USER
     # Paste contents of deploy/market-hours.cron
========================================================
INFO
