#!/usr/bin/env bash
# LAB-ONLY — requires a real GTPv2-C peer (open5gs MME on S11, or a running epc-host SGW-C).
# Sends a minimal GTPv2-C Echo Request (TS 29.274 §5.1, type 1) and asserts an
# Echo Response (type 2) with matching sequence number. Peer answer == live; LISTEN != live.
#
# Usage:
#   GTP_ECHO_HOST=10.20.0.10 GTP_ECHO_PORT=2123 ./gtp-echo-smoke.sh
#   ./gtp-echo-smoke.sh --host 10.20.0.10 --port 2123
#
# Exit 0 = Echo Response received; non-zero = no peer response / parse error.
set -euo pipefail

HOST="${GTP_ECHO_HOST:-127.0.0.1}"
PORT="${GTP_ECHO_PORT:-2123}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    -h|--help) grep -E '^# ' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

command -v python3 >/dev/null 2>&1 || { echo "python3 required" >&2; exit 2; }

HOST="$HOST" PORT="$PORT" python3 - <<'PY'
import os, socket, struct, sys, time

host = os.environ["HOST"]
port = int(os.environ["PORT"])
timeout = float(os.environ.get("GTP_ECHO_TIMEOUT", "5"))

seq = (int(time.time() * 1000) & 0xFFFFFF)  # 24-bit sequence number

# GTPv2-C header (TS 29.274): octet1 = version(3)/piggyback(1)/TEID(1)/spare(3)
# version 2 => bits 7-5 = 010 => 0x40; TEID flag bit 4 = 0x10 (clear here).
body = seq.to_bytes(3, "big") + b"\x00"        # sequence(3) + spare(1)
msg = bytes([0x40, 0x01]) + struct.pack("!H", len(body)) + body  # type 1 = Echo Request

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.settimeout(timeout)
try:
    sock.sendto(msg, (host, port))
    data, addr = sock.recvfrom(4096)
except socket.timeout:
    print(f"FAIL: no GTPv2-C Echo Response from {host}:{port} within {timeout}s")
    sys.exit(1)
finally:
    sock.close()

if len(data) < 8:
    print(f"FAIL: short GTPv2-C reply ({len(data)} bytes) from {addr[0]}:{addr[1]}")
    sys.exit(1)

version = (data[0] & 0xE0) >> 5
teid_flag = bool(data[0] & 0x10)
msg_type = data[1]
off = 8 if teid_flag else 4          # skip TEID(4) when present (header is 4+4)
resp_seq = int.from_bytes(data[off:off + 3], "big") if len(data) >= off + 3 else -1

evidence = f"version={version} type={msg_type} teid_flag={teid_flag} resp_seq=0x{resp_seq:06x} peer={addr[0]}:{addr[1]}"
if version != 2:
    print(f"FAIL: not GTPv2 (version={version}): {evidence}")
    sys.exit(1)
if msg_type != 2:  # Echo Response
    print(f"FAIL: expected Echo Response (type 2), got type {msg_type}: {evidence}")
    sys.exit(1)
if resp_seq != seq:
    print(f"FAIL: sequence mismatch (sent 0x{seq:06x}): {evidence}")
    sys.exit(1)

print(f"PASS: GTPv2-C Echo Response received from {addr[0]}:{addr[1]} (seq 0x{resp_seq:06x})")
PY