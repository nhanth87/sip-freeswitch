#!/usr/bin/env bash
# Ramp SIPp CPS against sip-servlet edge. Does not claim 10k until acceptance green.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCENARIO="$ROOT/loadtest/sipp/uac_invite_bye.xml"
HOST="${SIP_HOST:-127.0.0.1}"
PORT="${SIP_PORT:-5060}"
SERVICE="${SIP_SERVICE:-1000}"

if ! command -v sipp >/dev/null 2>&1; then
  echo "sipp not found on PATH — install SIPp, then re-run." >&2
  echo "Documented target: 10000 INVITE/s (see docs/agents/capacity-acceptance.md)" >&2
  exit 1
fi

FIXED_CPS="${1:-}"
DURATION="${2:-60}"

run_rate() {
  local rate="$1"
  local secs="$2"
  echo "=== SIPp rate=${rate} cps duration=${secs}s → ${HOST}:${PORT} ==="
  sipp -sf "$SCENARIO" -s "$SERVICE" "$HOST:$PORT" \
    -r "$rate" -rp 1000 -m $((rate * secs)) -l $((rate * 2)) \
    -trace_err -error_file "/tmp/sipp-err-${rate}.log" \
    -nostdin || true
}

if [[ -n "$FIXED_CPS" ]]; then
  run_rate "$FIXED_CPS" "$DURATION"
  exit 0
fi

for rate in 100 500 1000 2500 5000 7500 10000; do
  run_rate "$rate" 30
done

echo "Record max green CPS from SIPp stats; claim 10k only per capacity-acceptance.md"
echo "1M SIP TPS is cluster-scale (many generators + many P-CSCF) — docs/agents/capacity-1m.md"
