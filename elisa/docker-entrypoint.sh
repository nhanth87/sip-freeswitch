#!/bin/sh
# Wait for Postgres when SF_JDBC_URL is set (compose lab). ESL soft-fails if FS is down.
set -eu

JDBC_URL="${SF_JDBC_URL:-}"
if [ -n "$JDBC_URL" ]; then
  # jdbc:postgresql://host:port/db → host + port
  HOST=$(printf '%s' "$JDBC_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
  PORT=$(printf '%s' "$JDBC_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
  PORT="${PORT:-5432}"
  if [ -n "$HOST" ]; then
    echo "[elisa] waiting for postgres ${HOST}:${PORT}…"
    i=0
    while [ "$i" -lt 60 ]; do
      if curl -fsS --connect-timeout 1 "http://${HOST}:${PORT}/" >/dev/null 2>&1; then
        break
      fi
      # Postgres is not HTTP — use /dev/tcp when available, else sleep + let JDBC retry.
      if (echo >/dev/tcp/"$HOST"/"$PORT") >/dev/null 2>&1; then
        echo "[elisa] postgres port open"
        break
      fi
      i=$((i + 1))
      sleep 1
    done
  fi
fi

echo "[elisa] ESL soft-fail: SF_ESL_HOST=${SF_ESL_HOST:-unset} (eslLive=false until FS up)"
exec "$@"
