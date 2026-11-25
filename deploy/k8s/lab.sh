#!/bin/bash
# Elisa IMS + EPC on a local k3s (docs/agents/k8s.md). Run from the repo root.
#
#   deploy/k8s/lab.sh images      build the lab images and hand them to k3s
#   deploy/k8s/lab.sh up          namespaces, secrets, FreeSWITCH ConfigMap, helm install both charts
#   deploy/k8s/lab.sh status      pods / services
#   deploy/k8s/lab.sh epc-attach [N]   N attaches from the in-cluster MME harness, pcap + per-leg latency
#   deploy/k8s/lab.sh ims-call    provision lab subscribers, SIPp AKA REGISTER + call sip:1000, pcap
#   deploy/k8s/lab.sh down        helm uninstall both (PVCs kept)
#
# Needs: k3s running (kubeconfig /etc/rancher/k3s/k3s.yaml readable) and either a writable
# /var/lib/rancher/k3s/agent/images (k3s imports tarballs put there) or sudo for `k3s ctr images import`.
# Secrets are generated here into the cluster only — nothing secret is written to the repo.
set -euo pipefail
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
EPC_NS=epc
IMS_NS=elisa-ims
CHARTS=deploy/k8s/charts
PCAPS=loadtest/pcaps/k8s
LAB_IMAGES=(sip-freeswitch/elisa:lab sip-freeswitch/epc-host:lab sip-freeswitch/nginx-edge:lab
            postgres:16 coturn/coturn:4.6.2 safarov/freeswitch:latest)
IMAGES_DIR=/var/lib/rancher/k3s/agent/images

images() {
  # Prebuilt runtimes: ./tools/package-dist.sh (elisa/target/quarkus-app), (cd epc && mvn -pl epc-host -am package)
  docker build -q -t sip-freeswitch/elisa-base:lab elisa
  docker build -q -f deploy/k8s/images/elisa.Dockerfile --build-arg BASE=sip-freeswitch/elisa-base:lab \
    -t sip-freeswitch/elisa:lab .
  docker build -q -f epc/epc-host/Dockerfile -t sip-freeswitch/epc-host:lab epc
  docker build -q -t sip-freeswitch/nginx-edge:lab deploy/nginx
  if [ -w "$IMAGES_DIR" ]; then
    docker save "${LAB_IMAGES[@]}" -o "$IMAGES_DIR/sf-lab.tar.tmp"
    mv "$IMAGES_DIR/sf-lab.tar.tmp" "$IMAGES_DIR/sf-lab.tar"
    echo "images → $IMAGES_DIR/sf-lab.tar (k3s imports it)"
  else
    docker save "${LAB_IMAGES[@]}" | sudo k3s ctr images import -
  fi
}

secret() {  # namespace name key=value… — create once, never overwrite (keeps DB passwords stable)
  local ns=$1 name=$2; shift 2
  kubectl -n "$ns" get secret "$name" >/dev/null 2>&1 && return 0
  local args=()
  for kv in "$@"; do args+=(--from-literal="$kv"); done
  kubectl -n "$ns" create secret generic "$name" "${args[@]}" >/dev/null
  echo "secret $ns/$name created"
}

rnd() { openssl rand -hex 16; }

fs_configmap() {
  local d tmp
  d=freeswitch/conf
  tmp=$(mktemp -d)
  cp "$d/autoload_configs/event_socket.conf.xml" "$d/autoload_configs/acl.conf.xml" "$d/sip_profiles/disabled.xml" "$tmp/"
  # The S-CSCF owns :5060 in the pod; FreeSWITCH trunks on :5080 and fetches XML from its own S-CSCF.
  sed 's#name="sip-port" value="5060"#name="sip-port" value="5080"#' "$d/sip_profiles/external.xml" > "$tmp/external.xml"
  sed 's#http://scscf:8088/fs/xml#http://127.0.0.1:8088/fs/xml#' "$d/autoload_configs/xml_curl.scscf.conf.xml" > "$tmp/xml_curl.conf.xml"
  grep -q 'value="5080"' "$tmp/external.xml" && grep -q '127.0.0.1:8088' "$tmp/xml_curl.conf.xml"
  kubectl -n "$IMS_NS" create configmap freeswitch-conf --from-file="$tmp" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -rf "$tmp"
}

up() {
  kubectl create namespace "$EPC_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl create namespace "$IMS_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  secret "$EPC_NS" epc-db "password=$(rnd)"
  # ESL password must equal the one in freeswitch/conf/autoload_configs/event_socket.conf.xml.
  local esl
  esl=$(sed -n 's/.*name="password" value="\([^"]*\)".*/\1/p' freeswitch/conf/autoload_configs/event_socket.conf.xml)
  secret "$IMS_NS" elisa-db "password=$(rnd)"
  secret "$IMS_NS" elisa-lab "admin-api-key=$(rnd)" "admin-hmac-secret=$(rnd)" "admin-bootstrap-password=$(rnd)" \
    "esl-password=$esl" "turn-username=elisa" "turn-password=$(rnd)"
  fs_configmap
  helm upgrade --install epc "$CHARTS/epc" -n "$EPC_NS" --wait --timeout 10m
  helm upgrade --install elisa-ims "$CHARTS/elisa-ims" -n "$IMS_NS" --wait --timeout 10m
  status
}

status() {
  kubectl get pods -n "$EPC_NS" -o wide
  kubectl get pods -n "$IMS_NS" -o wide
  kubectl get svc -n "$IMS_NS" nginx-edge
  local bad
  bad=$(kubectl get pods -A --no-headers | grep -E "^($EPC_NS|$IMS_NS) " | grep -vE 'Running|Completed' || true)
  [ -z "$bad" ] && echo "all pods Running" || { echo "NOT running:"; echo "$bad"; }
}

capture_start() {  # name — tcpdump on the k3s pod bridge (host netns, no host sudo)
  mkdir -p "$PCAPS"
  docker rm -f k8s-cap >/dev/null 2>&1 || true
  docker run -d --name k8s-cap --net host -v "$PWD/$PCAPS:/cap" nicolaka/netshoot \
    tcpdump -i cni0 -s 0 -w "/cap/$1.pcap" "$2" >/dev/null
  sleep 3
}

capture_stop() { sleep 2; docker stop k8s-cap >/dev/null; docker rm k8s-cap >/dev/null; }

epc_attach() {
  local n=${1:-20}
  kubectl -n "$EPC_NS" create configmap mme-harness --from-file=loadtest/gtp/MmeS11.java \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$EPC_NS" delete pod mme --ignore-not-found --wait >/dev/null
  capture_start epc-attach "udp port 2123 or udp port 8805 or tcp port 3868"
  kubectl -n "$EPC_NS" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mme
  labels: {app.kubernetes.io/name: mme}
spec:
  restartPolicy: Never
  containers:
    - name: mme
      image: sip-freeswitch/epc-host:lab
      imagePullPolicy: Never
      env:
        - name: POD_IP
          valueFrom: {fieldRef: {fieldPath: status.podIP}}
      command:
        - bash
        - -c
        - |
          ok=0
          for i in \$(seq 1 $n); do
            java -cp "/work/lib/main/*:/work/app/*" /harness/MmeS11.java "\$POD_IP" epc-sgwc 001010000000010 internet > /tmp/a.out 2>&1
            [ "\$(grep -c 'Cause=16' /tmp/a.out)" -ge 5 ] && ok=\$((ok + 1))
            [ "\$i" -eq 1 ] && grep -E "RESPONSE|Cause=" /tmp/a.out | head -12
          done
          echo "ATTACHES=$n ALL-5-ACCEPTED=\$ok"
      volumeMounts:
        - {name: harness, mountPath: /harness}
  volumes:
    - name: harness
      configMap: {name: mme-harness}
EOF
  kubectl -n "$EPC_NS" wait --for=jsonpath='{.status.phase}'=Succeeded pod/mme --timeout=30m >/dev/null || true
  capture_stop
  kubectl -n "$EPC_NS" logs mme | tail -14
  python3 -I loadtest/gtp/attach-latency.py "$PCAPS/epc-attach.pcap" || true
  echo "malformed=$(tshark -r "$PCAPS/epc-attach.pcap" -Y _ws.malformed 2>/dev/null | wc -l)"
}

ims_call() {
  local ip k op khex ophex opc
  ip=$(kubectl get node -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
  # SIPp's USIM copies aka_K / aka_OP / aka_AMF as raw bytes: Ki = hex(K), OPc = AES_Ki(OP) xor OP, AMF "80".
  IFS=';' read -r _ k op < <(sed -n 2p loadtest/sipp/users-aka.csv)
  khex=$(printf %s "$k" | xxd -p)
  ophex=$(printf %s "$op" | xxd -p)
  opc=$(python3 -I -c 'import sys;a=bytes.fromhex(sys.argv[1]);b=bytes.fromhex(sys.argv[2]);print(bytes(x^y for x,y in zip(a,b)).hex())' \
        "$(printf %s "$op" | openssl enc -aes-128-ecb -K "$khex" -nopad | xxd -p)" "$ophex")
  local jar
  jar=$(mktemp)
  curl -s -o /dev/null -c "$jar" --data-urlencode username=admin --data-urlencode password=admin "http://$ip:8080/admin/login"
  curl -s -o /dev/null -w "lab-pair=%{http_code}\n" -b "$jar" -X POST "http://$ip:8080/admin/subscribers/lab-pair"
  curl -s -o /dev/null -w "akasipp=%{http_code}\n" -b "$jar" --data-urlencode sipUser=akasipp \
    --data-urlencode "displayName=AKA SIPp" --data-urlencode "ki=$khex" --data-urlencode "opc=$opc" \
    --data-urlencode amf=3830 "http://$ip:8080/admin/subscribers"
  rm -f "$jar"
  capture_start ims-call "udp port 5060 or tcp port 5060 or udp port 5080 or tcp port 3868"
  sipp -sf loadtest/sipp/uac_register_aka.xml -inf loadtest/sipp/users-aka.csv "$ip:5060" -p 16130 -m 1 \
    -timeout 30s -nostdin 2>&1 | grep -E "Successful call|Failed call"
  sipp -sf loadtest/sipp/uac_volte_call.xml -inf loadtest/sipp/users-aka.csv -s 1000 "$ip:5060" -p 16130 -m 1 \
    -timeout 60s -nostdin 2>&1 | grep -E "Successful call|Failed call"
  sipp -sf loadtest/sipp/uac_stray_bye.xml "$ip:5060" -p 16131 -m 1 -timeout 20s -nostdin 2>&1 \
    | grep -E "Successful call|Failed call"
  capture_stop
  tshark -r "$PCAPS/ims-call.pcap" -Y sip -T fields -e frame.time_relative -e ip.src -e ip.dst -e _ws.col.Info 2>/dev/null \
    | cut -c1-110
}

down() {
  helm uninstall elisa-ims -n "$IMS_NS" --wait 2>/dev/null || true
  helm uninstall epc -n "$EPC_NS" --wait 2>/dev/null || true
  kubectl -n "$EPC_NS" delete pod mme --ignore-not-found >/dev/null 2>&1 || true
}

case "${1:-}" in
  images) images ;;
  up) up ;;
  status) status ;;
  epc-attach) epc_attach "${2:-20}" ;;
  ims-call) ims_call ;;
  down) down ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
