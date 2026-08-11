#!/usr/bin/env bash
# Elisa IMS Gm smoke: tcpdump PCAP + SIPp REGISTER + tshark wire proof.
# Lab only — not Digicom production prove.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PCAP_DIR="${PCAP_DIR:-$ROOT/loadtest/pcaps}"
SCENARIO="${SCENARIO:-$ROOT/loadtest/ims-tools/sipp-scenarios/uac_register_challenge.xml}"
HOST="${SIP_HOST:-127.0.0.1}"
PORT="${SIP_PORT:-5060}"
SERVICE="${SIP_SERVICE:-alice}"
IFACE="${PCAP_IFACE:-any}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
PCAP="${PCAP_DIR}/elisa-ims-reg-${STAMP}.pcap"
SIPP_BIN="${SIPP_BIN:-}"
DURATION_CAP="${PCAP_SECONDS:-25}"

mkdir -p "$PCAP_DIR"

if [[ -z "$SIPP_BIN" ]]; then
  if [[ -x "$ROOT/loadtest/ims-tools/sipp/build/sipp" ]]; then
    SIPP_BIN="$ROOT/loadtest/ims-tools/sipp/build/sipp"
  elif command -v sipp >/dev/null 2>&1; then
    SIPP_BIN="$(command -v sipp)"
  else
    echo "sipp not found — build loadtest/ims-tools/sipp (see README) or set SIPP_BIN" >&2
    exit 1
  fi
fi

if ! command -v tcpdump >/dev/null 2>&1; then
  echo "tcpdump required for PCAP probe" >&2
  exit 1
fi
if ! command -v tshark >/dev/null 2>&1; then
  echo "tshark required for PCAP probe" >&2
  exit 1
fi

echo "=== PCAP capture → $PCAP (iface=$IFACE filter=udp/tcp port $PORT) ==="
# Prefer sudo if not root; fall back to unprivileged (may fail on some kernels).
TCPDUMP_CMD=(tcpdump -i "$IFACE" -s 0 -n -w "$PCAP" "udp port ${PORT} or tcp port ${PORT}")
if [[ "$(id -u)" -ne 0 ]]; then
  TCPDUMP_CMD=(sudo "${TCPDUMP_CMD[@]}")
fi

"${TCPDUMP_CMD[@]}" &
TCPDUMP_PID=$!
cleanup() {
  kill "$TCPDUMP_PID" 2>/dev/null || true
  wait "$TCPDUMP_PID" 2>/dev/null || true
}
trap cleanup EXIT
sleep 1

echo "=== SIPp REGISTER → ${HOST}:${PORT} service=${SERVICE} ==="
set +e
"$SIPP_BIN" -sf "$SCENARIO" -s "$SERVICE" "${HOST}:${PORT}" \
  -m 1 -l 1 -t u1 -i 127.0.0.1 \
  -trace_err -error_file "/tmp/sipp-ims-reg-err-${STAMP}.log" \
  -timeout 12 -timeout_error \
  -nostdin
SIPP_RC=$?
set -e

sleep 1
cleanup
trap - EXIT

echo "=== tshark wire proof ==="
REG_N=$(tshark -r "$PCAP" -Y 'sip.Method == REGISTER' 2>/dev/null | wc -l | tr -d ' ')
R401_N=$(tshark -r "$PCAP" -Y 'sip.Status-Code == 401' 2>/dev/null | wc -l | tr -d ' ')
INV_N=$(tshark -r "$PCAP" -Y 'sip.Method == INVITE' 2>/dev/null | wc -l | tr -d ' ')
SIP_N=$(tshark -r "$PCAP" -Y sip 2>/dev/null | wc -l | tr -d ' ')

echo "pcap=$PCAP"
echo "sip_frames=$SIP_N register=$REG_N status_401=$R401_N invite=$INV_N sipp_rc=$SIPP_RC"
tshark -r "$PCAP" -Y sip -T fields -e frame.number -e ip.src -e ip.dst -e sip.Method -e sip.Status-Code -e sip.Call-ID 2>/dev/null | head -40 || true

# Pass criteria: REGISTER on wire; prefer 401. SIPp exit alone is not enough.
if [[ "$REG_N" -lt 1 ]]; then
  echo "FAIL: no REGISTER on wire (pcap empty or wrong interface/filter)" >&2
  exit 2
fi
if [[ "$R401_N" -ge 1 ]]; then
  echo "PASS: REGISTER + 401 on wire (PCAP probe)"
  exit 0
fi
echo "SOFT-FAIL: REGISTER on wire but no 401 (Elisa down, no subscriber, or non-401 reject). Inspect pcap." >&2
exit 3
