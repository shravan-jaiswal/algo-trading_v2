#!/usr/bin/env bash
# backup-and-deploy.sh
# Backs up the local PostgreSQL database, builds the fat JAR,
# copies both to VPS, restores the DB, and restarts the service.
#
# Usage (from project root, via Git Bash or WSL):
#   bash deploy/backup-and-deploy.sh <user@vps-host>
#
# Example:
#   bash deploy/backup-and-deploy.sh root@203.0.113.10
#
# Requirements on local machine:
#   - pg_dump accessible (add PostgreSQL bin dir to PATH, e.g. C:\Program Files\PostgreSQL\18\bin)
#   - ssh / scp available (Git Bash includes both)
#   - Maven (mvn) on PATH

set -euo pipefail

VPS_HOST="${1:-}"
if [[ -z "$VPS_HOST" ]]; then
  echo "Usage: $0 <user@vps-host>"
  exit 1
fi

# ── Config ────────────────────────────────────────────────────────────────────
LOCAL_DB_NAME="algo_db_v2"
LOCAL_DB_USER="algo"
LOCAL_DB_HOST="localhost"
LOCAL_DB_PORT="5432"

REMOTE_DB_NAME="algo_db_v2"
REMOTE_DB_USER="algo"

JAR_NAME="algo-trading-v2-2.0.0.jar"
REMOTE_DIR="/opt/algo-trading_v2"
SERVICE="algo-trading_v2"

DUMP_FILE="algo_db_v2_$(date +%Y%m%d_%H%M%S).dump"
BACKUP_DIR="deploy/backups"
# ─────────────────────────────────────────────────────────────────────────────

mkdir -p "$BACKUP_DIR"

# 1. Dump local database
echo "==> [1/5] Dumping local database '$LOCAL_DB_NAME'..."
pg_dump \
  -h "$LOCAL_DB_HOST" \
  -p "$LOCAL_DB_PORT" \
  -U "$LOCAL_DB_USER" \
  -F c \
  -f "$BACKUP_DIR/$DUMP_FILE" \
  "$LOCAL_DB_NAME"
echo "    Saved: $BACKUP_DIR/$DUMP_FILE ($(du -sh "$BACKUP_DIR/$DUMP_FILE" | cut -f1))"

# Keep only the last 7 backups locally
ls -t "$BACKUP_DIR"/*.dump 2>/dev/null | tail -n +8 | xargs -r rm --

# 2. Build fat JAR
echo "==> [2/5] Building fat JAR..."
mvn package -q -DskipTests
echo "    Built: target/$JAR_NAME"

# 3. Copy JAR + DB dump to VPS
echo "==> [3/5] Uploading JAR and DB dump to VPS..."
scp "target/$JAR_NAME"        "$VPS_HOST:$REMOTE_DIR/"
scp "$BACKUP_DIR/$DUMP_FILE"  "$VPS_HOST:/tmp/$DUMP_FILE"
echo "    Upload complete"

# 4. Restore DB on VPS
echo "==> [4/5] Restoring database on VPS..."
ssh "$VPS_HOST" bash <<REMOTE
  set -euo pipefail

  echo "    Stopping service before restore..."
  systemctl stop $SERVICE 2>/dev/null || true

  echo "    Dropping and recreating database..."
  sudo -u postgres psql -c "DROP DATABASE IF EXISTS $REMOTE_DB_NAME;"
  sudo -u postgres psql -c "CREATE DATABASE $REMOTE_DB_NAME OWNER $REMOTE_DB_USER;"

  echo "    Restoring dump..."
  sudo -u postgres pg_restore \
    -d $REMOTE_DB_NAME \
    -U postgres \
    --no-owner \
    --role=$REMOTE_DB_USER \
    /tmp/$DUMP_FILE

  rm -f /tmp/$DUMP_FILE
  echo "    Database restored"
REMOTE

# 5. Start service
echo "==> [5/5] Starting service..."
ssh "$VPS_HOST" "systemctl start $SERVICE && systemctl status $SERVICE --no-pager -l"

echo ""
echo "==> Done. Tail logs with:"
echo "    ssh $VPS_HOST 'journalctl -u $SERVICE -f'"
echo "    ssh $VPS_HOST 'tail -f $REMOTE_DIR/logs/algo-trading_v2.log'"
