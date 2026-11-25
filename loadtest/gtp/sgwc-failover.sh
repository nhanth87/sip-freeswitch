#!/bin/bash
# SGW-C failover on epc-cluster, with pcap (docs/agents/epc-sgw-pgw-wire.md "Clustering").
#   Create Session on epc-sgwc (.14) -> kill it -> Modify / Release / Modify / Delete on epc-sgwc-2 (.24).
# Run from the repo root after `mvn -pl epc-host -am package` and the image build.
# Output: loadtest/pcaps/epc/epc-sgwc-failover.pcap, loadtest/pcaps/epc/failover/*.log
set -u
OUT=loadtest/pcaps/epc/failover
mkdir -p "$OUT"
F=(-f docker-compose.yml -f docker-compose.services.yml -f docker-compose.epc-wire.yml -f docker-compose.epc-cluster.yml --profile epc)
BRIDGE="br-$(docker network inspect sip-freeswitch_sf-net -f '{{.Id}}' | cut -c1-12)"
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}
export PATH=$JAVA_HOME/bin:$PATH
CP_FILE=$(mktemp)
(cd epc && mvn -o -q -pl epc-sgwc dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null 2>&1)
CP="$(cat "$CP_FILE")"

docker compose "${F[@]}" up -d --force-recreate postgres-epc epc-sgwc epc-sgwc-2 epc-pgwc epc-pcrf epc-up-linux
for _ in $(seq 1 80); do
  n=$(docker ps --filter health=healthy --format '{{.Names}}' | grep -cE 'epc-(sgwc-1|sgwc-2-1|pgwc-1|pcrf-1|up-linux-1)')
  [ "$n" -ge 5 ] && break; sleep 3
done
echo "healthy=$n"
docker logs sip-freeswitch-epc-sgwc-2-1 2>&1 | grep ISPN000094 | tail -1 | cut -c1-200

# Warm-up attach on node 1 (first CCA of a cold PCRF is slow).
java -cp "$CP" loadtest/gtp/MmeS11.java 172.20.0.1 172.20.10.14 001010000000010 internet > "$OUT/warmup.out" 2>&1
grep -c "Cause=16" "$OUT/warmup.out" | sed 's/^/warmup cause16 lines: /'

docker rm -f epc-cap-failover >/dev/null 2>&1
docker run -d --name epc-cap-failover --net host -v "$PWD/loadtest/pcaps/epc:/cap" nicolaka/netshoot \
  tcpdump -i "$BRIDGE" -s 0 -w /cap/epc-sgwc-failover.pcap "udp port 2123 or udp port 8805 or tcp port 3868" >/dev/null
sleep 3
java -cp "$CP" loadtest/gtp/MmeS11.java 172.20.0.1 172.20.10.14 001010000000011 internet 172.20.0.1 172.20.10.24 20 \
  > "$OUT/mme.out" 2>&1 &
MME=$!
for _ in $(seq 1 40); do grep -q FAILOVER "$OUT/mme.out" && break; sleep 0.5; done
grep -E "CREATE_SESSION_RESPONSE|FAILOVER|NO " "$OUT/mme.out"
docker logs sip-freeswitch-epc-sgwc-1 > "$OUT/sgwc-1.log" 2>&1
docker kill sip-freeswitch-epc-sgwc-1 >/dev/null && echo "KILLED epc-sgwc-1 $(date +%T)"
wait $MME; echo "mme exit $?"
sleep 3
docker stop epc-cap-failover >/dev/null
docker logs sip-freeswitch-epc-sgwc-2-1 > "$OUT/sgwc-2.log" 2>&1
grep -E "RESPONSE|^  Cause=" "$OUT/mme.out"
echo "MISSING_ENTITY node2: $(grep -c MISSING_ENTITY "$OUT/sgwc-2.log")"
grep -E "taken over" "$OUT/sgwc-2.log" | tail -3 | cut -c1-200
rm -f "$CP_FILE"
echo FAILOVER-DONE
