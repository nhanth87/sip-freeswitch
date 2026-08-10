#!/usr/bin/env bash
set -euo pipefail
# Compose edge = nginx :8080; local ./run.sh = Elisa :8088
BASE="${SMOKE_BASE:-}"
if [[ -z "$BASE" ]]; then
  if curl -sf -o /dev/null "http://127.0.0.1:8080/admin/login" 2>/dev/null; then
    BASE="http://127.0.0.1:8080"
  else
    BASE="http://127.0.0.1:8088"
  fi
fi
code="$(curl -s -o /tmp/sf-health.json -w '%{http_code}' "$BASE/q/health" || true)"
if [[ "$code" != "200" ]]; then
  echo "FAIL: /q/health -> HTTP $code (BASE=$BASE; compose nginx :8080 or ./run.sh :8088)" >&2
  exit 1
fi
login="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/login" || true)"
if [[ "$login" != "200" ]]; then
  echo "FAIL: /admin/login -> HTTP $login (BASE=$BASE)" >&2
  exit 1
fi
# Edge must not expose xml_curl (N7) when BASE is nginx
if [[ "$BASE" == *":8080"* ]] || [[ "$BASE" == *":8443"* ]]; then
  fs="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/fs/xml" || true)"
  if [[ "$fs" != "403" && "$fs" != "404" ]]; then
    echo "FAIL: /fs/xml via edge expected 403, got HTTP $fs" >&2
    exit 1
  fi
fi
echo "OK: health + login page (BASE=$BASE)"
