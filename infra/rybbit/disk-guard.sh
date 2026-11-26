#!/usr/bin/env bash
# Disk safety net for the Rybbit log sink — "never run out of disk".
# Daily: reclaim Docker build cache + unused images. If disk >= 85%, emergency-reclaim:
# force ClickHouse TTL expiry, trim oldest backups, full prune. NEVER touches data volumes.
# Installed to /opt/rybbit-deploy/disk-guard.sh; root cron at 04:00 daily.
set -euo pipefail
LOG=/var/log/rybbit-diskguard.log
THRESHOLD=85

# Routine reclaim (Watchtower already removes superseded app images via --cleanup).
docker builder prune -af >>"$LOG" 2>&1 || true
docker image prune -af >>"$LOG" 2>&1 || true

USED=$(df --output=pcent / | tail -1 | tr -dc '0-9')
echo "$(date -Is) disk used=${USED}%" >>"$LOG"

if [ "${USED:-0}" -ge "$THRESHOLD" ]; then
  echo "$(date -Is) disk >= ${THRESHOLD}% — emergency reclaim" >>"$LOG"
  for t in events bot_events session_replay_events; do
    docker exec clickhouse clickhouse-client --query "ALTER TABLE analytics.$t MATERIALIZE TTL" >>"$LOG" 2>&1 || true
  done
  ls -1t /opt/rybbit-backups/weekly/clickhouse-*.tar.gz 2>/dev/null | tail -n +3 | xargs -r rm -f
  ls -1t /opt/rybbit-backups/daily/postgres-*.sql 2>/dev/null | tail -n +4 | xargs -r rm -f
  # No --volumes: ClickHouse/Postgres/Redis data volumes must survive.
  docker system prune -af >>"$LOG" 2>&1 || true
fi
