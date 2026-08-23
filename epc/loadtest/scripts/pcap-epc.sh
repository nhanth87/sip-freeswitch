#!/usr/bin/env bash
# tshark capture + analysis for the EPC control plane (and optional Elisa SIP overlay).
#
# Capture ports: Diameter S6a/Cx 3868, GTPv2-C S11/S5 2123/udp, PFCP Sxa/Sxb 8805/udp.
# Optional SIP (Elisa only, NOT EPC): set PCAP_SIP=1 to also capture 5060/5061.
#
# Usage:
#   sudo ./pcap-epc.sh capture                     # capture to pcaps/epc-<ts>.pcap
#   ./pcap-epc.sh analyze <capture.pcap>           # per-plane dissection summary
#   ./pcap-epc.sh sidecar [seconds]                # capture via compose `pcap` service (no sudo)
#
# Env:
#   PCAP_IFACE    interface (default: any)
#   PCAP_OUT      output pcap (default: pcaps/epc-<ts>.pcap)
#   PCAP_SECONDS  capture duration (default: 120)
#   PCAP_FILE_SIZE_MB ring filesize MB (default: 20)
#   PCAP_SNAP     snaplen bytes (default: 262144)
#   PCAP_SIP=1    also capture SIP 5060/5061 (Elisa plane)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PCAP_DIR="${PCAP_DIR:-$LOADTEST_DIR/pcaps}"

IFACE="${PCAP_IFACE:-any}"
PCAP_SECONDS="${PCAP_SECONDS:-120}"
PCAP_FILE_SIZE_MB="${PCAP_FILE_SIZE_MB:-20}"
PCAP_SNAP="${PCAP_SNAP:-262144}"

command -v tshark >/dev/null 2>&1 || { echo "tshark required (wireshark-cli)" >&2; exit 2; }

capture_filter='port 3868 or udp port 2123 or udp port 8805'
[[ "${PCAP_SIP:-0}" == "1" ]] && capture_filter+=' or port 5060 or port 5061'

cmd="${1:-help}"; shift || true

case "$cmd" in
  capture)
    mkdir -p "$PCAP_DIR"
    OUT="${PCAP_OUT:-$PCAP_DIR/epc-$(date +%Y%m%d-%H%M%S).pcap}"
    echo "== EPC control-plane capture =="
    echo "iface:        $IFACE"
    echo "filter:       $capture_filter"
    echo "out:          $OUT"
    echo "duration:     ${PCAP_SECONDS}s  | ring: ${PCAP_FILE_SIZE_MB}MB | snaplen: ${PCAP_SNAP}"
    echo "display keys: diameter | gtpv2 | pfcp${PCAP_SIP:+ | sip.Method}"
    echo
    exec tshark -i "$IFACE" -f "$capture_filter" -s "$PCAP_SNAP" \
      -b "filesize:$((PCAP_FILE_SIZE_MB * 1024))" \
      -b "files:$(( (PCAP_SECONDS / 60) + 2 ))" \
      -a "duration:$PCAP_SECONDS" -w "$OUT"
    ;;

  sidecar)
    # Capture from inside sf-net via the compose `pcap` (netshoot) service —
    # no host sudo required; the pcap lands in epc/loadtest/pcaps/.
    mkdir -p "$PCAP_DIR"
    SECONDS_CAP="${1:-${PCAP_SECONDS:-60}}"
    OUT="epc-wire-$(date +%Y%m%d-%H%M%S).pcap"
    REPO_ROOT="$(cd "$LOADTEST_DIR/../.." && pwd)"
    (cd "$REPO_ROOT" && docker compose -f docker-compose.yml -f docker-compose.services.yml \
      --profile epc run --rm -T pcap \
      tshark -i eth0 -f 'port 3868 or udp port 2123 or udp port 8805' \
      -s "$PCAP_SNAP" -a "duration:$SECONDS_CAP" -w "/pcaps/$OUT")
    echo "CAPTURED: $PCAP_DIR/$OUT"
    echo "analyze: $0 analyze $PCAP_DIR/$OUT"
    ;;

  analyze)
    PCAP="${1:-}"
    [[ -n "$PCAP" ]] || { echo "usage: $0 analyze <capture.pcap>" >&2; exit 2; }
    [[ -f "$PCAP" ]] || { echo "pcap not found: $PCAP" >&2; exit 2; }
    echo "== protocol hierarchy =="
    tshark -r "$PCAP" -q -z io,phs 2>/dev/null || true
    echo
    while IFS='|' read -r plane filter; do
      count="$(tshark -r "$PCAP" -Y "$filter" 2>/dev/null | wc -l | tr -d ' ')"
      echo "== $plane ($filter): $count packets =="
      tshark -r "$PCAP" -Y "$filter" -T fields -e _ws.col.Info 2>/dev/null | sort | uniq -c | sort -rn | head -10
      echo
    done <<'FILTERS'
diameter|diameter
gtpv2|gtpv2
pfcp|pfcp
sip|sip.Method
FILTERS
    ;;

  -h|--help|help)
    grep -E '^# ' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;

  *)
    echo "unknown command: $cmd (expected capture|analyze)" >&2
    exit 2
    ;;
esac