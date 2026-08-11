#!/usr/bin/env bash
# IMS SIP smoke + PCAP probe — must-run on Elisa SIP code change (lab).
# Fail-closed if Elisa :5060 is down unless IMS_SMOKE_ALLOW_SKIP=1.
# Not Digicom production prove.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

PCAP_DIR="${PCAP_DIR:-$ROOT/loadtest/pcaps}"
SCENARIO="${SCENARIO:-$ROOT/loadtest/ims-tools/sipp-scenarios/uac_register_challenge.xml}"
HOST="${SIP_HOST:-127.0.0.1}"
PORT="${SIP_PORT:-5060}"
SERVICE="${SIP_SERVICE:-alice}"
IFACE="${PCAP_IFACE:-lo}"
LOCAL_PORT="${SIP_LOCAL_PORT:-5069}"
PCAP="${PCAP_DIR}/on-change-$$.pcap"
SIPP_BIN="${SIPP_BIN:-}"
REQUIRE_401="${IMS_SMOKE_REQUIRE_401:-0}"
ALLOW_SKIP="${IMS_SMOKE_ALLOW_SKIP:-0}"

mkdir -p "$PCAP_DIR"

resolve_sipp() {
  if [[ -n "$SIPP_BIN" && -x "$SIPP_BIN" ]]; then return 0; fi
  if [[ -x "$ROOT/loadtest/ims-tools/sipp/build/sipp" ]]; then
    SIPP_BIN="$ROOT/loadtest/ims-tools/sipp/build/sipp"; return 0
  fi
  if [[ -x "$ROOT/loadtest/third_party/sipp/build/sipp" ]]; then
    SIPP_BIN="$ROOT/loadtest/third_party/sipp/build/sipp"; return 0
  fi
  if command -v sipp >/dev/null 2>&1; then
    SIPP_BIN="$(command -v sipp)"; return 0
  fi
  return 1
}

port_open() {
  # UDP/TCP listener check — ss preferred
  ss -ltnu 2>/dev/null | grep -qE ":${PORT}\\b" && return 0
  # TCP connect probe (Elisa often binds UDP+TCP)
  timeout 1 bash -c "echo >/dev/tcp/${HOST}/${PORT}" 2>/dev/null && return 0
  return 1
}

echo "=== IMS on-change smoke (PCAP mandatory) ==="
echo "target=${HOST}:${PORT} scenario=$(basename "$SCENARIO") pcap=$PCAP"

if ! command -v tcpdump >/dev/null 2>&1 || ! command -v tshark >/dev/null 2>&1; then
  echo "FAIL: tcpdump and tshark are required for PCAP probe" >&2
  exit 10
fi

if ! resolve_sipp; then
  echo "FAIL: sipp not found — build loadtest/ims-tools/sipp (see README)" >&2
  exit 11
fi

if ! port_open; then
  msg="Elisa SIP not listening on ${HOST}:${PORT}. Start lab: docker compose up -d  OR  dist/elisa/run.sh (after postgres)."
  if [[ "$ALLOW_SKIP" == "1" ]]; then
    echo "SKIP (IMS_SMOKE_ALLOW_SKIP=1): $msg" >&2
    exit 0
  fi
  echo "FAIL (fail-closed): $msg" >&2
  echo "Hint: set IMS_SMOKE_ALLOW_SKIP=1 only for non-SIP worktrees; SIP/IMS changes must run with Elisa up." >&2
  exit 12
fi

# Capture filter includes SIPp local port so REGISTER appears even on lo.
FILTER="udp port ${PORT} or tcp port ${PORT} or udp port ${LOCAL_PORT} or tcp port ${LOCAL_PORT}"
PCAP_MODE=none
start_tcpdump() {
  if [[ "$(id -u)" -eq 0 ]]; then
    tcpdump -i "$IFACE" -s 0 -n -w "$PCAP" "$FILTER" &
    TCPDUMP_PID=$!; PCAP_MODE=tcpdump-root; return 0
  fi
  if sudo -n true 2>/dev/null; then
    sudo -n tcpdump -i "$IFACE" -s 0 -n -w "$PCAP" "$FILTER" &
    TCPDUMP_PID=$!; PCAP_MODE=tcpdump-sudo; return 0
  fi
  # Docker NET_RAW fallback (host tcpdump often needs CAP_NET_RAW / sudo)
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker rm -f elisa-ims-pcap-$$ >/dev/null 2>&1 || true
    if docker run -d --name "elisa-ims-pcap-$$" --net=host --cap-add=NET_RAW \
        -v "$(dirname "$PCAP"):/pcaps" \
        alpine:3.20 \
        sh -c "apk add --no-cache tcpdump >/dev/null && tcpdump -i ${IFACE} -s 0 -n -w /pcaps/$(basename "$PCAP") \"$FILTER\"" \
        >/tmp/elisa-ims-pcap-ctr.$$ 2>/tmp/elisa-ims-pcap-err.$$; then
      TCPDUMP_PID=""; PCAP_MODE=docker-alpine
      PCAP_CTR="elisa-ims-pcap-$$"
      sleep 2
      return 0
    fi
  fi
  echo "FAIL: cannot start PCAP capture (need sudo CAP_NET_RAW or docker --cap-add=NET_RAW)" >&2
  exit 13
}

start_tcpdump
cleanup() {
  if [[ "${PCAP_MODE}" == docker-alpine ]]; then
    docker rm -f "${PCAP_CTR:-}" >/dev/null 2>&1 || true
    # alpine writes as root — best-effort chown
    sudo -n chown "$(id -u):$(id -g)" "$PCAP" 2>/dev/null || true
  else
    kill -INT "$TCPDUMP_PID" 2>/dev/null || kill "$TCPDUMP_PID" 2>/dev/null || true
    wait "$TCPDUMP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
sleep 1

# -p LOCAL_PORT avoids colliding with Elisa listening on :5060
set +e
"$SIPP_BIN" -sf "$SCENARIO" -s "$SERVICE" "${HOST}:${PORT}" \
  -m 1 -l 1 -t u1 -i 127.0.0.1 -p "$LOCAL_PORT" \
  -trace_err -error_file "/tmp/sipp-on-change-$$.log" \
  -timeout 12 -timeout_error \
  -nostdin
SIPP_RC=$?
set -e

sleep 1
cleanup
trap - EXIT

REG_N=$(tshark -r "$PCAP" -Y 'sip.Method == REGISTER' 2>/dev/null | wc -l | tr -d ' ')
R401_N=$(tshark -r "$PCAP" -Y 'sip.Status-Code == 401' 2>/dev/null | wc -l | tr -d ' ')
R2XX_N=$(tshark -r "$PCAP" -Y 'sip.Status-Code >= 200 && sip.Status-Code < 300' 2>/dev/null | wc -l | tr -d ' ')
SIP_N=$(tshark -r "$PCAP" -Y sip 2>/dev/null | wc -l | tr -d ' ')

echo "pcap=$PCAP sip_frames=$SIP_N register=$REG_N status_401=$R401_N status_2xx=$R2XX_N sipp_rc=$SIPP_RC"
tshark -r "$PCAP" -Y sip -T fields -e frame.number -e ip.src -e ip.dst -e sip.Method -e sip.Status-Code 2>/dev/null | head -30 || true

if [[ "$REG_N" -lt 1 ]]; then
  echo "FAIL: no REGISTER on wire — PCAP probe failed (wrong iface/filter or SIPp silent)" >&2
  exit 2
fi

if [[ "$REQUIRE_401" == "1" ]]; then
  if [[ "$R401_N" -ge 1 ]]; then
    echo "PASS: REGISTER + 401 on wire"
    exit 0
  fi
  echo "FAIL: IMS_SMOKE_REQUIRE_401=1 but no 401 in pcap" >&2
  exit 3
fi

# Default: any SIP response to REGISTER proves Gm path (401 preferred)
RESP_N=$(tshark -r "$PCAP" -Y 'sip.Status-Code' 2>/dev/null | wc -l | tr -d ' ')
if [[ "$RESP_N" -ge 1 || "$R401_N" -ge 1 ]]; then
  echo "PASS: REGISTER on wire with SIP response (PCAP probe)"
  exit 0
fi

echo "FAIL: REGISTER sent but no SIP response in pcap (Elisa not answering?)" >&2
exit 4
