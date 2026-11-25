#!/bin/bash
# Warm Create Session latency per leg (docs/agents/epc-sgw-pgw-wire.md "Latency").
#   3 warm-up attaches, then N attaches (Create / Modify / Release / Modify / Delete each) under tcpdump,
#   then loadtest/gtp/attach-latency.py on the capture.
# Run from the repo root after `mvn -pl epc-host -am package` and the image build.
# Output: loadtest/pcaps/epc/epc-attach-warm.pcap, loadtest/pcaps/epc/latency/*.out
set -u
N=${1:-50}
OUT=loadtest/pcaps/epc/latency
mkdir -p "$OUT"
F=(-f docker-compose.yml -f docker-compose.services.yml -f docker-compose.epc-wire.yml --profile epc)
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}
export PATH=$JAVA_HOME/bin:$PATH
CP_FILE=$(mktemp)
(cd epc && mvn -o -q -pl epc-sgwc dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null 2>&1)
CP="$(cat "$CP_FILE")"

docker compose "${F[@]}" up -d postgres-epc epc-sgwc epc-pgwc epc-pcrf epc-up-linux
for _ in $(seq 1 80); do
  n=$(docker ps --filter health=healthy --format '{{.Names}}' | grep -cE 'epc-(sgwc-1|pgwc-1|pcrf-1|up-linux-1)')
  [ "$n" -ge 4 ] && break; sleep 3
done
echo "healthy=$n"
BRIDGE="br-$(docker network inspect sip-freeswitch_sf-net -f '{{.Id}}' | cut -c1-12)"

for i in 1 2 3; do
  java -cp "$CP" loadtest/gtp/MmeS11.java 172.20.0.1 172.20.10.14 001010000000010 internet > "$OUT/warmup-$i.out" 2>&1
done
echo "warm-up cause16 lines: $(cat "$OUT"/warmup-*.out | grep -c 'Cause=16')"

docker rm -f epc-cap-latency >/dev/null 2>&1
docker run -d --name epc-cap-latency --net host -v "$PWD/loadtest/pcaps/epc:/cap" nicolaka/netshoot \
  tcpdump -i "$BRIDGE" -s 0 -w /cap/epc-attach-warm.pcap "udp port 2123 or udp port 8805 or tcp port 3868" >/dev/null
sleep 3
ok=0
for i in $(seq 1 "$N"); do
  java -cp "$CP" loadtest/gtp/MmeS11.java 172.20.0.1 172.20.10.14 001010000000010 internet > "$OUT/attach.out" 2>&1
  [ "$(grep -c 'Cause=16' "$OUT/attach.out")" -ge 5 ] && ok=$((ok + 1))
done
sleep 2
docker stop epc-cap-latency >/dev/null
echo "attaches=$N all-5-accepted=$ok"
python3 -I loadtest/gtp/attach-latency.py loadtest/pcaps/epc/epc-attach-warm.pcap
echo "malformed=$(tshark -r loadtest/pcaps/epc/epc-attach-warm.pcap -Y _ws.malformed 2>/dev/null | wc -l)"
rm -f "$CP_FILE"
