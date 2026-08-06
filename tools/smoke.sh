#!/usr/bin/env bash
set -euo pipefail
BASE="${SMOKE_BASE:-http://127.0.0.1:8088}"
code="$(curl -s -o /tmp/sf-health.json -w '%{http_code}' "$BASE/q/health" || true)"
if [[ "$code" != "200" ]]; then
  echo "FAIL: /q/health -> HTTP $code (is ./run.sh up? need postgres)" >&2
  exit 1
fi
login="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/login" || true)"
if [[ "$login" != "200" ]]; then
  echo "FAIL: /admin/login -> HTTP $login" >&2
  exit 1
fi
echo "OK: health + login page"
