#!/usr/bin/env bash
# Rebuild voice-pcmu.pcap: English speech (espeak-ng) → 8 kHz G.711 μ-law → 20 ms RTP (PT 0)
# in an Ethernet/IPv4/UDP pcap that SIPp's play_pcap_audio replays.
set -euo pipefail
cd "$(dirname "$0")"
TEXT=${1:-"Hello. This is Alice, calling through the Elisa I M S core. The S C S C F routes this call to the FreeSWITCH media server. You should hear the tone coming back. Thank you, goodbye."}
tmp=$(mktemp -d)
espeak-ng -v en-us -s 140 -w "$tmp/voice.wav" "$TEXT"
ffmpeg -hide_banner -loglevel error -y -i "$tmp/voice.wav" -ar 8000 -ac 1 -f mulaw "$tmp/voice.ulaw"
cp "$tmp/voice.ulaw" voice.ulaw   # raw G.711 μ-law for SIPp rtp_stream
python3 -I - "$tmp/voice.ulaw" voice-pcmu.pcap <<'PY'
import random, struct, sys
data = open(sys.argv[1], 'rb').read()
out = open(sys.argv[2], 'wb')
out.write(struct.pack('<IHHiIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
ssrc, seq, ts = random.getrandbits(32), random.getrandbits(16), random.getrandbits(32)
def csum(b):
    s = sum(struct.unpack('!10H', b)); s = (s >> 16) + (s & 0xffff); s += s >> 16
    return ~s & 0xffff
for n, off in enumerate(range(0, len(data), 160)):
    rtp = struct.pack('!BBHII', 0x80, 0x80 if n == 0 else 0, (seq + n) & 0xffff,
                      (ts + 160 * n) & 0xffffffff, ssrc) + data[off:off + 160].ljust(160, b'\xff')
    udp = struct.pack('!HHHH', 6000, 6000, 8 + len(rtp), 0) + rtp
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(udp), n & 0xffff, 0, 64, 17, 0,
                     bytes([127, 0, 0, 1]), bytes([127, 0, 0, 1]))
    ip = ip[:10] + struct.pack('!H', csum(ip)) + ip[12:]
    frame = b'\0' * 12 + b'\x08\x00' + ip + udp
    t = 0.02 * n
    out.write(struct.pack('<IIII', int(t), int(round((t - int(t)) * 1e6)), len(frame), len(frame)) + frame)
PY
rm -rf "$tmp"
echo "voice-pcmu.pcap rebuilt"
