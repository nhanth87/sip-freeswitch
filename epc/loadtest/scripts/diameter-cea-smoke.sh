#!/usr/bin/env bash
# LAB-ONLY — requires a real Diameter peer (open5gs MME S6a / pyhss / DRA, or a running
# epc-host / Elisa with Cx/S6a). Sends a minimal RFC 6733 CER (command 257, R set) and
# asserts a CEA (command 257, R clear) carrying Result-Code 2001 (DIAMETER_SUCCESS).
# Peer answer == live; listening socket == NOT live.
#
# Usage:
#   DIAMETER_HOST=10.20.0.11 DIAMETER_PORT=3868 ./diameter-cea-smoke.sh
#   ./diameter-cea-smoke.sh --host 10.20.0.11 --port 3868
#
# Exit 0 = CEA with Result-Code 2001; non-zero = no CEA / rejected / parse error.
set -euo pipefail

HOST="${DIAMETER_HOST:-127.0.0.1}"
PORT="${DIAMETER_PORT:-3868}"
APP_ID="${DIAMETER_APP_ID:-16777251}"   # S6a; override for Cx=16777216, Gx=16777238, Gy=4
ORIGIN_HOST="${DIAMETER_ORIGIN_HOST:-epc-test.epc.lab}"
ORIGIN_REALM="${DIAMETER_ORIGIN_REALM:-epc.lab}"
ORIGIN_IP="${DIAMETER_ORIGIN_IP:-127.0.0.1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --app-id) APP_ID="$2"; shift 2 ;;
    -h|--help) grep -E '^# ' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

command -v python3 >/dev/null 2>&1 || { echo "python3 required" >&2; exit 2; }

HOST="$HOST" PORT="$PORT" APP_ID="$APP_ID" ORIGIN_HOST="$ORIGIN_HOST" \
ORIGIN_REALM="$ORIGIN_REALM" ORIGIN_IP="$ORIGIN_IP" python3 - <<'PY'
import os, socket, struct, sys, time

host = os.environ["HOST"]
port = int(os.environ["PORT"])
app_id = int(os.environ["APP_ID"])
origin_host = os.environ["ORIGIN_HOST"]
origin_realm = os.environ["ORIGIN_REALM"]
origin_ip = os.environ["ORIGIN_IP"]
timeout = float(os.environ.get("DIAMETER_TIMEOUT", "5"))

# --- AVP helpers -------------------------------------------------------------
# AVP: code(32) | flags(8: V=0x80 M=0x40) | length(24) | [vendor-id(32) if V] | data(padded)
def avp(code, flags, data):
    pad = (-len(data)) % 4
    body = data + (b"\x00" * pad)
    return (code.to_bytes(4, "big") + bytes([flags])
            + (len(body) + 8).to_bytes(3, "big") + body)

def u32(v):
    return struct.pack("!I", v)

def octets(s):
    return s.encode("utf-8")

def addr4(ip):
    # RFC 6733 Address AVP: 16-bit Address-Type (1=IPv4) + 4 octets
    return struct.pack("!H", 1) + socket.inet_aton(ip)

# --- Build CER ---------------------------------------------------------------
avps = [
    avp(264, 0x40, octets(origin_host)),   # Origin-Host (M)
    avp(296, 0x40, octets(origin_realm)),  # Origin-Realm (M)
    avp(257, 0x40, addr4(origin_ip)),      # Host-IP-Address (M)
    avp(266, 0x40, u32(0)),                # Vendor-Id (M)
    avp(269, 0x00, octets("epc-loadtest")),# Product-Name
    avp(278, 0x00, u32(1)),                # Origin-State-Id
    avp(267, 0x00, u32(1)),                # Firmware-Revision
    avp(258, 0x00, u32(app_id)),           # Auth-Application-Id
    avp(265, 0x00, u32(10415)),            # Supported-Vendor-Id (3GPP)
]
body = b"".join(avps)
hop = int(time.time()) & 0xFFFFFFFF
msg_len = 20 + len(body)
header = (bytes([0x01])                        # Version = 1
          + msg_len.to_bytes(3, "big")
          + bytes([0x80])                      # command flags: R (request)
          + (257).to_bytes(3, "big")           # Command-Code = CER (CEA)
          + struct.pack("!III", 0, hop, 0))    # App-Id=0, Hop-by-Hop, End-to-End
cer = header + body

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.settimeout(timeout)
try:
    sock.connect((host, port))
    sock.sendall(cer)
    data = b""
    while len(data) < 20:
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk
    if len(data) >= 4:
        total = int.from_bytes(data[1:4], "big")
        while len(data) < total:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
except (socket.timeout, ConnectionRefusedError, OSError) as e:
    print(f"FAIL: no CEA from {host}:{port}: {e}")
    sys.exit(1)
finally:
    sock.close()

if len(data) < 20:
    print(f"FAIL: short Diameter reply ({len(data)} bytes) from {host}:{port}")
    sys.exit(1)

ver = data[0]
mlen = int.from_bytes(data[1:4], "big")
cflags = data[4]
ccode = int.from_bytes(data[5:8], "big")
req = bool(cflags & 0x80)

# Scan AVPs for Result-Code(268), Origin-Host(264)
result_code = None
resp_host = None
off = 20
while off + 8 <= mlen:
    code = int.from_bytes(data[off:off + 4], "big")
    flags = data[off + 4]
    alen = int.from_bytes(data[off + 5:off + 8], "big")
    has_vendor = bool(flags & 0x80)
    vskip = 4 if has_vendor else 0
    dlen = alen - 8 - vskip
    if dlen < 0:
        break  # malformed AVP; stop parsing rather than risk looping
    doff = off + 8 + vskip
    if dlen <= (len(data) - doff):
        dval = data[doff:doff + dlen]
        if code == 268 and dlen >= 4:
            result_code = struct.unpack("!I", dval[:4])[0]
        elif code == 264:
            resp_host = dval.decode("utf-8", "replace")
    off = doff + ((dlen + 3) & ~3)

evidence = (f"version={ver} cmd={ccode} request={req} "
            f"result_code={result_code} origin_host={resp_host or '-'} peer={host}:{port}")
if ver != 1:
    print(f"FAIL: not Diameter (version={ver}): {evidence}")
    sys.exit(1)
if ccode != 257 or req:
    print(f"FAIL: expected CEA (cmd 257, R clear), got request={req} cmd={ccode}: {evidence}")
    sys.exit(1)
if result_code != 2001:
    print(f"FAIL: peer rejected or unexpected Result-Code ({result_code}): {evidence}")
    sys.exit(1)

print(f"PASS: CEA received from {host}:{port} — Result-Code 2001 (DIAMETER_SUCCESS), "
      f"origin-host={resp_host or '<undecoded>'}")
PY