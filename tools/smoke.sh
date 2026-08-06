#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${SMOKE_BASE:-http://127.0.0.1:18080}"

code="$(curl -s -o /tmp/sf-health.json -w '%{http_code}' "$BASE/q/health" || true)"
if [[ "$code" != "200" ]]; then
  echo "FAIL: /q/health -> HTTP $code (is ./run.sh up?)" >&2
  exit 1
fi
grep -q UP /tmp/sf-health.json
admin="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/" || true)"
if [[ "$admin" != "200" ]]; then
  echo "FAIL: /admin/ -> HTTP $admin" >&2
  exit 1
fi
echo "OK: health + admin"
