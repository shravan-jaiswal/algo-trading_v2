#!/usr/bin/env bash
# deploy.sh — build fat JAR locally and push to VPS
# Usage: ./deploy/deploy.sh [vps-host]
# Example: ./deploy/deploy.sh algo@203.0.113.10
# Assumes ~/.ssh/id_rsa has access to VPS.

set -euo pipefail

VPS_HOST="${1:-}"
if [[ -z "$VPS_HOST" ]]; then
  echo "Usage: $0 <user@vps-host>"
  exit 1
fi

JAR_NAME="algo-trading-v2-2.0.0.jar"
REMOTE_DIR="/opt/algo-trading_v2"
SERVICE="algo-trading_v2"

echo "==> Building fat JAR..."
mvn package -q -DskipTests

echo "==> Copying JAR to $VPS_HOST:$REMOTE_DIR/"
scp "target/$JAR_NAME" "$VPS_HOST:$REMOTE_DIR/"

echo "==> Restarting service on VPS..."
ssh "$VPS_HOST" "sudo systemctl restart $SERVICE && sudo systemctl status $SERVICE --no-pager -l"

echo ""
echo "==> Deploy complete. Tail logs with:"
echo "    ssh $VPS_HOST 'sudo journalctl -u $SERVICE -f'"
echo "    ssh $VPS_HOST 'tail -f $REMOTE_DIR/logs/algo-trading_v2.log'"
