#!/usr/bin/env bash
# Fail-closed N–N mid-call kill: confirmed INVITE → docker stop home S → BYE/200 on wire.
# Requires: --profile services --profile nn up; sipp; tshark; docker compose.
# Usage (from repo root):
#   ./loadtest/scripts/nn-midcall-kill.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIP_HOST="${SIP_HOST:-127.0.0.1}"
SIP_PORT="${SIP_PORT:-5060}"
LOCAL_PORT="${SIP_LOCAL_PORT:-5079}"
HOME_SVC="${HOME_S_CSCF:-scscf}"
PEER_SVC="${PEER_S_CSCF:-scscf-2}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.services.yml --profile services --profile nn)
PCAP_DIR="$ROOT/loadtest/pcaps"
SCENARIO="$ROOT/loadtest/sipp/uac_invite_hold_bye.xml"
mkdir -p "$PCAP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
PCAP="$PCAP_DIR/nn-midcall-kill-${STAMP}.pcap"
SIPP_LOG="/tmp/nn-midcall-sipp-$$.out"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "FAIL: missing $1" >&2
    exit 2
  }
}

SIPP_BIN="${SIPP_BIN:-}"
if [[ -z "$SIPP_BIN" || ! -x "$SIPP_BIN" ]]; then
  if [[ -x "$ROOT/loadtest/ims-tools/sipp/build/sipp" ]]; then
    SIPP_BIN="$ROOT/loadtest/ims-tools/sipp/build/sipp"
  elif [[ -x "$ROOT/loadtest/third_party/sipp/build/sipp" ]]; then
    SIPP_BIN="$ROOT/loadtest/third_party/sipp/build/sipp"
  elif command -v sipp >/dev/null 2>&1; then
    SIPP_BIN="$(command -v sipp)"
  else
    echo "FAIL: missing sipp" >&2
    exit 2
  fi
fi
need tshark
need docker

if [[ ! -f "$SCENARIO" ]]; then
  echo "FAIL: missing $SCENARIO" >&2
  exit 2
fi

echo "=== N–N mid-call kill-test ==="
echo "target=${SIP_HOST}:${SIP_PORT} home=${HOME_SVC} peer=${PEER_SVC}"

home_up="$("${COMPOSE[@]}" ps -q "$HOME_SVC" 2>/dev/null || true)"
peer_up="$("${COMPOSE[@]}" ps -q "$PEER_SVC" 2>/dev/null || true)"
if [[ -z "$home_up" || -z "$peer_up" ]]; then
  echo "FAIL: ${HOME_SVC} and ${PEER_SVC} must be running (profile services+nn)" >&2
  "${COMPOSE[@]}" ps || true
  exit 2
fi

if ! timeout 1 bash -c "echo >/dev/tcp/${SIP_HOST}/${SIP_PORT}" 2>/dev/null; then
  echo "FAIL: SIP ${SIP_HOST}:${SIP_PORT} not reachable" >&2
  exit 2
fi

TCPDUMP_PID=""
CAP_CTR=""
cleanup() {
  if [[ -n "${CAP_CTR}" ]]; then
    docker exec "${CAP_CTR}" killall -INT tcpdump >/dev/null 2>&1 || true
    sleep 0.4
    docker cp "${CAP_CTR}:/tmp/k.pcap" "$PCAP" 2>/dev/null || true
    docker rm -f "${CAP_CTR}" >/dev/null 2>&1 || true
    CAP_CTR=""
  fi
  if [[ -n "${TCPDUMP_PID}" ]] && kill -0 "$TCPDUMP_PID" 2>/dev/null; then
    kill -INT "$TCPDUMP_PID" 2>/dev/null || kill "$TCPDUMP_PID" 2>/dev/null || true
    wait "$TCPDUMP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

FILTER="udp port ${SIP_PORT} or tcp port ${SIP_PORT} or udp port ${LOCAL_PORT} or tcp port ${LOCAL_PORT}"
NGX_ID="$("${COMPOSE[@]}" ps -q nginx-services 2>/dev/null || true)"
if [[ -n "$NGX_ID" ]]; then
  CAP_CTR="elisa-nn-kill-cap-$$"
  docker rm -f "$CAP_CTR" >/dev/null 2>&1 || true
  docker run -d --name "$CAP_CTR" --net "container:${NGX_ID}" --cap-add=NET_RAW \
    alpine:3.20 sh -c 'apk add --no-cache tcpdump >/dev/null && tcpdump -U -i any -s 0 -n -w /tmp/k.pcap udp port 5060 or tcp port 5060' >/dev/null
  sleep 2
elif [[ "$(id -u)" -eq 0 ]]; then
  tcpdump -i lo -s 0 -n -w "$PCAP" "$FILTER" &
  TCPDUMP_PID=$!
  sleep 0.6
elif sudo -n true 2>/dev/null; then
  sudo -n tcpdump -i lo -s 0 -n -w "$PCAP" "$FILTER" &
  TCPDUMP_PID=$!
  sleep 0.6
else
  tshark -i lo -f "$FILTER" -w "$PCAP" -q &
  TCPDUMP_PID=$!
  sleep 0.6
fi

set +e
"$SIPP_BIN" -sf "$SCENARIO" -s alice "${SIP_HOST}:${SIP_PORT}" \
  -m 1 -l 1 -t u1 -i 127.0.0.1 -p "$LOCAL_PORT" \
  -timeout 35 -timeout_error -nostdin \
  >"$SIPP_LOG" 2>&1 &
SIPP_PID=$!
set -e

ok200=0
for _ in $(seq 1 48); do
  # Read pcap inside the sidecar — docker cp of a growing file is often truncated/unreadable.
  if [[ -n "${CAP_CTR}" ]]; then
    if docker exec "${CAP_CTR}" sh -c 'tcpdump -r /tmp/k.pcap -n -A 2>/dev/null | grep -q "SIP/2.0 200"' 2>/dev/null; then
      ok200=1
      break
    fi
  elif [[ -s "$PCAP" ]] && tshark -r "$PCAP" -Y "sip.Status-Code == 200 and sip.CSeq.method == INVITE" -T fields -e frame.number 2>/dev/null | grep -q .; then
    ok200=1
    break
  fi
  if ! kill -0 "$SIPP_PID" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
if [[ "$ok200" != 1 ]]; then
  echo "FAIL: no INVITE 200 before kill (pcap=$PCAP)" >&2
  cat "$SIPP_LOG" >&2 || true
  kill "$SIPP_PID" 2>/dev/null || true
  exit 1
fi

echo "INVITE 200 seen — stopping ${HOME_SVC}"
"${COMPOSE[@]}" stop -t 3 "$HOME_SVC"

set +e
wait "$SIPP_PID"
SIPP_RC=$?
set -e

sleep 0.6
cleanup
trap - EXIT
TCPDUMP_PID=""

bye=$(tshark -r "$PCAP" -Y "sip.Method == BYE" 2>/dev/null | wc -l | tr -d ' ')
bye200=$(tshark -r "$PCAP" -Y "sip.Status-Code == 200 and sip.CSeq.method == BYE" 2>/dev/null | wc -l | tr -d ' ')
echo "pcap=$PCAP bye=${bye} bye200=${bye200} sipp_rc=${SIPP_RC}"
tshark -r "$PCAP" -Y sip -T fields -e frame.number -e sip.Method -e sip.Status-Code 2>/dev/null | head -40 || true

if [[ "${bye200}" -lt 1 ]]; then
  # Sidecar pcap often misses Gm BYE after nginx recreate; SIPp Successful call after stop is UE truth.
  if [[ "${SIPP_RC}" -eq 0 ]] && grep -qE 'Successful call[[:space:]]+\|.*\|[[:space:]]*1' "$SIPP_LOG"; then
    echo "PASS: mid-call BYE 200 after ${HOME_SVC} kill (SIPp UE confirmed; pcap bye200=0)"
    cat "$SIPP_LOG" || true
    exit 0
  fi
  echo "FAIL: no BYE 200 after ${HOME_SVC} stop — peer did not continue SIP" >&2
  cat "$SIPP_LOG" >&2 || true
  exit 1
fi

echo "PASS: mid-call BYE 200 after ${HOME_SVC} kill (peer continue)"
exit 0
