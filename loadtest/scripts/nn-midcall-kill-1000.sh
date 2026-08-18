#!/usr/bin/env bash
# N–N mid-call kill (proper call): REGISTER alice -> INVITE 1000 (FS MRF) -> 200,
# then docker stop the HOME S-CSCF alice is bound to, and require the PEER to
# answer the UE BYE with 200 (dialog ownership in ISPN DIST_SYNC + failover).
# Requires: --profile services --profile nn up; sipp; tshark; docker compose.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIP_HOST="${SIP_HOST:-127.0.0.1}"
SIP_PORT="${SIP_PORT:-5060}"
LOCAL_PORT="${SIP_LOCAL_PORT:-5092}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.services.yml --profile services --profile nn)
PCAP_DIR="$ROOT/loadtest/pcaps"
SCENARIO="$ROOT/loadtest/sipp/uac_midcall_reg_invite_1000.xml"
mkdir -p "$PCAP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
PCAP="$PCAP_DIR/nn-midcall-kill-1000-${STAMP}.pcap"
SIPP_LOG="/tmp/nn-midcall1000-sipp-$$.out"

need() { command -v "$1" >/dev/null 2>&1 || { echo "FAIL: missing $1" >&2; exit 2; }; }

SIPP_BIN="${SIPP_BIN:-}"
if [[ -z "$SIPP_BIN" || ! -x "$SIPP_BIN" ]]; then
  if [[ -x "$ROOT/loadtest/ims-tools/sipp/build/sipp" ]]; then SIPP_BIN="$ROOT/loadtest/ims-tools/sipp/build/sipp"
  elif command -v sipp >/dev/null 2>&1; then SIPP_BIN="$(command -v sipp)"
  else echo "FAIL: missing sipp" >&2; exit 2; fi
fi
need tshark; need docker

if [[ ! -f "$SCENARIO" ]]; then echo "FAIL: missing $SCENARIO" >&2; exit 2; fi

if ! timeout 1 bash -c "echo >/dev/tcp/${SIP_HOST}/${SIP_PORT}" 2>/dev/null; then
  echo "FAIL: SIP ${SIP_HOST}:${SIP_PORT} not reachable" >&2; exit 2
fi

echo "=== N–N mid-call kill (1000 path) ==="
echo "target=${SIP_HOST}:${SIP_PORT}"

# home S-CSCF alice is bound to (query HSS-lite PG after REGISTER below).
home_svc() {
  docker exec sip-freeswitch-postgres-1 psql -U elisa -d elisa -t -A -c \
    "SELECT CASE WHEN home_scscf_node LIKE '%scscf-2%' THEN 'scscf-2' ELSE 'scscf' END FROM ims_binding WHERE impu='sip:alice@ims.lab' ORDER BY updated_at DESC LIMIT 1;" 2>/dev/null | tr -d ' '
}

NGX_ID="$(docker inspect -f '{{.Id}}' sip-freeswitch-nginx-services-1 2>/dev/null || "${COMPOSE[@]}" ps -q nginx-services)"
CAP_CTR=""
cleanup() {
  if [[ -n "${CAP_CTR}" ]]; then
    docker exec "${CAP_CTR}" killall -INT tcpdump >/dev/null 2>&1 || true
    sleep 0.4
    docker cp "${CAP_CTR}:/tmp/k.pcap" "$PCAP" 2>/dev/null || true
    docker rm -f "${CAP_CTR}" >/dev/null 2>&1 || true
    CAP_CTR=""
  fi
}
trap cleanup EXIT

CAP_CTR="elisa-nn-kill1000-cap-$$"
docker rm -f "$CAP_CTR" >/dev/null 2>&1 || true
docker run -d --name "$CAP_CTR" --net "container:${NGX_ID}" --cap-add=NET_RAW \
  alpine:3.20 sh -c 'apk add --no-cache tcpdump >/dev/null && tcpdump -U -i any -s 0 -n -w /tmp/k.pcap udp port 5060 or tcp port 5060' >/dev/null
sleep 2

set +e
"$SIPP_BIN" -sf "$SCENARIO" -s alice "${SIP_HOST}:${SIP_PORT}" \
  -m 1 -l 1 -t u1 -i 127.0.0.1 -p "$LOCAL_PORT" \
  -timeout 40 -timeout_error -nostdin >"$SIPP_LOG" 2>&1 &
SIPP_PID=$!
set -e

ok200=0
for _ in $(seq 1 60); do
  if docker exec "${CAP_CTR}" sh -c 'tcpdump -r /tmp/k.pcap -n -A 2>/dev/null | grep -q "SIP/2.0 200"' 2>/dev/null; then
    ok200=1; break
  fi
  if ! kill -0 "$SIPP_PID" 2>/dev/null; then break; fi
  sleep 0.25
done
if [[ "$ok200" != 1 ]]; then
  echo "FAIL: no INVITE 200 before kill (pcap=$PCAP)" >&2
  cat "$SIPP_LOG" >&2 || true
  kill "$SIPP_PID" 2>/dev/null || true
  exit 1
fi

HOME="$(home_svc)"
[[ -z "$HOME" ]] && HOME="scscf"
PEER="$([[ "$HOME" == "scscf-2" ]] && echo scscf || echo scscf-2)"
echo "INVITE 200 seen — stopping HOME S-CSCF ${HOME} (peer=${PEER})"
"${COMPOSE[@]}" stop -t 3 "$HOME"

set +e
wait "$SIPP_PID"
SIPP_RC=$?
set -e
sleep 0.6
cleanup
trap - EXIT

bye=$(tshark -r "$PCAP" -Y "sip.Method == BYE" 2>/dev/null | wc -l | tr -d ' ')
bye200=$(tshark -r "$PCAP" -Y "sip.Status-Code == 200 and sip.CSeq.method == BYE" 2>/dev/null | wc -l | tr -d ' ')
echo "pcap=$PCAP bye=${bye} bye200=${bye200} sipp_rc=${SIPP_RC} home=${HOME}"
tshark -r "$PCAP" -Y sip -T fields -e frame.number -e ip.src -e ip.dst -e sip.Method -e sip.Status-Code 2>/dev/null | head -40 || true

if [[ "${bye200}" -ge 1 ]]; then
  echo "PASS: mid-call BYE 200 after ${HOME} kill (peer continue)"
  exit 0
fi
if [[ "${SIPP_RC}" -eq 0 ]] && grep -qE 'Successful call[[:space:]]+\|.*\|[[:space:]]*1' "$SIPP_LOG"; then
  echo "PASS: mid-call BYE 200 after ${HOME} kill (SIPp UE confirmed; pcap bye200=0)"
  cat "$SIPP_LOG" || true
  exit 0
fi
echo "FAIL: no BYE 200 after ${HOME} stop — peer did not continue SIP" >&2
cat "$SIPP_LOG" >&2 || true
exit 1
