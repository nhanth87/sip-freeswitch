#!/bin/bash
# srsUE + srsENB (ZMQ) + open5gs MME attach against our core, with pcap (docs/agents/epc-ran-interop.md).
# Run from the repo root. Needs: images built (epc-host package + compose build), subscriber seeded
# (epc/loadtest/scripts/epc-seed-subscriber.sh), epc/loadtest/ran/ue.env.
# Output: loadtest/pcaps/ran/ran-attach.pcap (core side), enb_s1ap.pcap / ue_nas.pcap (RAN side), logs.
set -u
F=(-f docker-compose.yml -f docker-compose.services.yml -f docker-compose.epc-wire.yml -f docker-compose.epc-ran.yml --profile epc)
OUT=loadtest/pcaps/ran
mkdir -p "$OUT"
BR="br-$(docker network inspect sip-freeswitch_sf-net -f '{{.Id}}' | cut -c1-12)"
docker rm -f sip-freeswitch-srsue-1 sip-freeswitch-srsenb-1 ran-cap >/dev/null 2>&1
docker compose "${F[@]}" up -d --force-recreate postgres-epc epc-hss mme epc-sgwc epc-pgwc epc-pcrf epc-up-linux >/dev/null 2>&1
for _ in $(seq 1 60); do
  docker logs sip-freeswitch-mme-1 2>&1 | grep -q "CONNECTED TO 'epc-hss" && break; sleep 3
done
docker logs sip-freeswitch-mme-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep "CONNECTED TO" | tail -1
for _ in $(seq 1 40); do
  n=$(docker ps --filter health=healthy --format '{{.Names}}' | grep -cE 'epc-(sgwc-1|pgwc-1|pcrf-1|up-linux-1)')
  [ "$n" -ge 4 ] && break; sleep 3
done
docker run -d --name ran-cap --net host -v "$PWD/$OUT:/cap" nicolaka/netshoot \
  tcpdump -i "$BR" -s 0 -w /cap/ran-attach.pcap "sctp or tcp port 3868 or udp port 2123 or udp port 8805 or udp port 2152" >/dev/null
sleep 3
docker compose "${F[@]}" up -d srsenb >/dev/null 2>&1
sleep 10
docker compose "${F[@]}" up -d srsue >/dev/null 2>&1
for _ in $(seq 1 60); do
  docker logs sip-freeswitch-srsue-1 2>&1 | grep -qE "Network attach successful|Attach failed \(attempt 2" && break; sleep 2
done
sleep 5
docker stop ran-cap >/dev/null
docker logs sip-freeswitch-srsue-1 > "$OUT/srsue.log" 2>&1
docker logs sip-freeswitch-mme-1 2>&1 | sed "s/\x1b\[[0-9;]*m//g" > "$OUT/mme-docker.log"
docker logs sip-freeswitch-epc-hss-1 > "$OUT/hss.log" 2>&1
grep -E "RRC Connected|Attach|Network attach|IP:" "$OUT/srsue.log" | tail -5
grep -vE "md_hook_cb_tree|^\s*\(\(null\)" "$OUT/mme-docker.log" | grep -E "ERROR|Attach|Authentication|Security|Session|PDN|Bearer" | tail -12 | cut -c1-200
echo RAN-DONE
