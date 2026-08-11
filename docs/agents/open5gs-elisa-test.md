# Open5GS × Elisa IMS lab test

**Date:** 2026-08-10  
**Workspace:** `worktrees/voice-service/sip-freeswitch`  
**Scope:** Lab only. **Not** Digicom prove-artifact. Elisa = CSCF (+ HSS-lite), **not** AMF/SMF/UPF, **not** Rx/N5 owner.

Companion inventory: [`ims-external-test-report.md`](ims-external-test-report.md)  
PCAPs: [`loadtest/pcaps/open5gs-elisa/`](../../loadtest/pcaps/open5gs-elisa/)

---

## Honest verdict

| Claim | Result |
|-------|--------|
| **Full VoLTE/VoNR E2E** (UE → RAN → 5GC/EPC IMS APN → Gm REGISTER 200 → INVITE + RTP) | **NOT achieved** — no real UE/RAN in this run |
| Open5GS EPC up with **IMS DNN + P-CSCF → Elisa** | **PASS** (config + SMF live) |
| Diameter CER/DWR inside Open5GS (MME↔HSS, SMF↔PCRF) | **PASS** (pcap) |
| Gm REGISTER toward Elisa at SMF-advertised P-CSCF IP | **PASS on wire** — `REGISTER` + **`403 Forbidden`** (not 401) |
| Authenticated REGISTER → 200 + Path/Service-Route | **FAIL** this session (403 after HSS-lite insert; AKA challenge path not green) |
| INVITE MO signaling answered | **FAIL** — INVITEs on wire, no SIP response observed |
| Rx/N5 QoS from Elisa → Open5GS PCRF/PCF | **N/A** — Elisa does not own Rx/N5 |

**Bottom line:** Open5GS can be pointed at **external Elisa P-CSCF** via SMF `p-cscf:` / subscriber IMS APN. That integration surface was brought up and proven on wire for **Gm reachability**. It is **not** a full IMS E2E pass without UE/RAN + healthy AKA REGISTER.

---

## Topology (what ran)

```text
                    ┌─────────────────────────────────────────┐
  (no UE/RAN)       │  docker_open5gs 4g-external-ims         │
                    │  mongo webui hss mme sgwc/sgwu smf upf  │
                    │  pcrf pyhss dns ocs osmo* grafana       │
                    │                                         │
                    │  SMF session.p-cscf: [192.168.1.13]     │──┐
                    └─────────────────────────────────────────┘  │
                                                                 │ Gm (lab)
                    host Elisa (dist/elisa/run.sh)                │
                    :5060 SIP  :8088 admin                       ◄┘
                    Postgres+FS+coturn via sip-freeswitch compose
                    ISPN disabled (protostream jar skew)
```

`4g-external-ims-deploy.yaml` = **EPC + PyHSS + OCS, no Kamailio/OpenSIPS CSCF** — P-CSCF is external (Elisa).

---

## How Open5GS talks to external IMS / P-CSCF

1. **SMF** (`smf/smf.yaml` → runtime `/open5gs/install/etc/open5gs/smf.yaml`):
   - DNN/APN `ims` session pool
   - `session.p-cscf: [ PCSCF_IP ]` substituted from `.env` at container init
2. **Subscriber** (Open5GS HSS/UDR via webui / `open5gs-dbctl`): must include APN/DNN **`ims`** (plus `internet`)
3. **UE** learns P-CSCF from NAS/PCO after IMS PDN/PDU session — then sends **Gm SIP** to that address (Elisa), **not** through SMF/UPF as SIP proxy
4. Elisa does **not** replace AMF/SMF/UPF; Open5GS does **not** replace CSCF

Live evidence this run:

```yaml
# loadtest/pcaps/open5gs-elisa/smf-session-pcscf.yaml (excerpt)
p-cscf:
  - 192.168.1.13
```

`.env`:

```bash
DOCKER_HOST_IP=192.168.1.13
PCSCF_IP=192.168.1.13   # host LAN — Elisa SIP VIP / host :5060
```

---

## Exact commands used

### 1. Elisa (host dist — compose image failed ISPN protostream)

Compose `elisa` container still exits on `ClassNotFoundException: org.infinispan.protostream.impl.ResourceUtils` when ISPN enabled. Lab workaround:

```bash
# free :9090 (Open5GS metrics collides with Elisa prometheus-exporter-ra)
docker stop metrics   # optional

PG_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' sip-freeswitch-postgres-1)
FS_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' sip-freeswitch-freeswitch-1)

setsid bash -c "
  cd dist/elisa
  export SF_HTML_DIR=\$PWD/app/html
  export SF_JDBC_URL=jdbc:postgresql://${PG_IP}:5432/elisa
  export SF_DB_USER=elisa SF_DB_PASSWORD=elisa
  export SF_ESL_HOST=${FS_IP} SF_ESL_PORT=8021 SF_ESL_PASSWORD=ClueCon
  export SF_TRUNK_FREESWITCH_URI=sip:1000@${FS_IP}:5060
  export SF_IMS_ISC_AS=sip:1000@${FS_IP}:5060
  export SF_FS_CONF_DIR=\$PWD/../../freeswitch/conf
  export SF_ADMIN_API_KEY=sf-admin
  export SF_ADMIN_SESSION_HMAC_SECRET=change-me-sf-lab
  export SF_ADMIN_BOOTSTRAP_PASSWORD=admin
  export SF_ADMIN_DEV_LOGIN=true
  export SF_CLUSTER_ISPN_ENABLED=false
  export SF_CLUSTER_ISPN_MODE=LOCAL
  export SF_HTTP_PORT=8088
  exec ./run.sh
" </dev/null >>/tmp/elisa-host-run.log 2>&1 &

curl -fsS http://127.0.0.1:8088/q/health
```

`docker-compose.yml` also gained lab secrets (`SF_ADMIN_*`, `SF_FS_CONF_DIR`, `SF_IMS_ISC_AS`) so a future image rebuild can start once ISPN jars are fixed.

### 2. Open5GS external-IMS stack

```bash
cd loadtest/ims-tools/docker_open5gs
# pull prebuilts once
docker pull ghcr.io/herlesupreeth/docker_open5gs:master && docker tag … docker_open5gs
# (+ docker_dns, docker_mysql, docker_pyhss, docker_ocs, docker_osmohlr, docker_osmomsc, docker_metrics)

# point P-CSCF at host Elisa SIP
sed -i 's|^PCSCF_IP=.*|PCSCF_IP=192.168.1.13|' .env
sed -i 's|^DOCKER_HOST_IP=.*|DOCKER_HOST_IP=192.168.1.13|' .env

set -a; source .env; set +a
docker compose -f 4g-external-ims-deploy.yaml up -d
```

**UPF lab patch:** vendor YAML uses `network_mode: host`, which failed to bind `172.22.0.8`. Patched to compose-network `UPF_IP` so PFCP associates. Soft-fail / re-apply if you reset the vendored file.

### 3. Provision Open5GS subscriber + IMS APN

```bash
docker exec webui misc/db/open5gs-dbctl add_ue_with_apn \
  001011234567895 8baf473f2f8fd09487cccbd7097c6862 8E27B6AF0E692E750F32667A3B14605D internet
docker exec webui misc/db/open5gs-dbctl update_apn 001011234567895 ims 1
```

Log: `loadtest/pcaps/open5gs-elisa/subscriber-provision.log`

### 4. PCAP + SIPp (Gm as post–P-CSCF-discovery UE)

```bash
# capture (host often lacks CAP_NET_RAW — use docker)
docker run -d --rm --name open5gs-elisa-pcap --net=host --cap-add=NET_RAW --cap-add=NET_ADMIN \
  -v "$PWD/loadtest/pcaps/open5gs-elisa:/pcaps" alpine:3.20 \
  sh -c 'apk add --no-cache tcpdump >/dev/null && tcpdump -i any -s 0 -w /pcaps/session-all.pcap \
    "udp port 5060 or tcp port 5060 or port 3868 or port 5868"'

# IMPORTANT: SIPp local port must NOT be 5060 (Elisa already binds it)
SIPP=loadtest/ims-tools/sipp/build/sipp
$SIPP 127.0.0.1:5060 -i 127.0.0.1 -p 15060 \
  -sf loadtest/ims-tools/sipp-scenarios/uac_register_challenge.xml \
  -m 1 -s 001011234567895 -timeout 12

$SIPP 192.168.1.13:5060 -i 192.168.1.13 -p 15061 \
  -sf loadtest/ims-tools/sipp-scenarios/uac_register_challenge.xml \
  -m 1 -s 001011234567895 -timeout 10

tshark -r loadtest/pcaps/open5gs-elisa/session-final.pcap -Y 'sip.Method == REGISTER'
tshark -r loadtest/pcaps/open5gs-elisa/session-final.pcap -Y 'sip.Status-Code == 403'
tshark -r loadtest/pcaps/open5gs-elisa/session-final.pcap -Y 'diameter.cmd.code == 257'
```

Elisa HSS-lite insert (still saw 403 this session):

```bash
docker exec -i sip-freeswitch-postgres-1 psql -U elisa -d elisa -c \
  "INSERT INTO ims_subscriber (impi,impu,ki,opc,amf,sqn,msisdn,enabled) VALUES
   ('001011234567895@ims.lab','sip:001011234567895@ims.lab',
    '8baf473f2f8fd09487cccbd7097c6862','8E27B6AF0E692E750F32667A3B14605D',
    '8000',0,'9076543210',true)
   ON CONFLICT (impi) DO UPDATE SET ki=EXCLUDED.ki, opc=EXCLUDED.opc, enabled=true;"
```

---

## Test matrix (this session)

| # | Case | Tool | Wire assert | Result |
|---|------|------|-------------|--------|
| O1 | SMF IMS + P-CSCF config | docker exec smf | `p-cscf: 192.168.1.13` | **PASS** |
| O2 | Open5GS UE + IMS APN | open5gs-dbctl | subscriber JSON has `ims` | **PASS** |
| O3 | SMF→Elisa IP reachability | docker exec smf | udp to `:5060` | **PASS** |
| O4 | Diameter CER (257) Open5GS | tshark | count ≥ 1 | **PASS** (4) |
| O5 | Diameter DWR (280) | tshark | count ≥ 1 | **PASS** (79) |
| O6 | Gm REGISTER → Elisa @127.0.0.1 | SIPp `-p 15060` | REGISTER + status | **PASS** path; **403** not 401 |
| O7 | Gm REGISTER → Elisa @PCSCF_IP | SIPp `-p 15061` | REGISTER + 403 | **PASS** path |
| O8 | REGISTER → 401 AKA | SIPp + ims_subscriber | `sip.Status-Code==401` | **FAIL** (0) |
| O9 | INVITE signaling | SIPp invite | INVITE + 1xx/2xx/4xx | **FAIL** (INVITE on wire, no response) |
| O10 | UE attach + IMS PDN + Gm from UE APN | srsUE / phone | — | **NOT RUN** (no RAN/UE) |
| O11 | Rx/N5 dedicated bearer | PCRF/PCF | — | **N/A** (not Elisa) |

---

## PCAP paths

| File | Notes |
|------|-------|
| `loadtest/pcaps/open5gs-elisa/session-final.pcap` | Full session freeze (~244 KB) |
| `loadtest/pcaps/open5gs-elisa/session-all.pcap` | Same capture |
| `loadtest/pcaps/open5gs-elisa/smf-session-pcscf.yaml` | Live SMF session/P-CSCF excerpt |
| `loadtest/pcaps/open5gs-elisa/subscriber-provision.log` | Open5GS IMS APN provision |
| `loadtest/pcaps/open5gs-elisa/SUMMARY.txt` | Counts + health snapshot |
| `loadtest/pcaps/open5gs-elisa/sipp-reg4*.out` | First clean REGISTER→403 |
| `loadtest/pcaps/open5gs-elisa/sipp-reg6*.out` | After Elisa `ims_subscriber` insert (still 403) |

Wire counts (`session-final.pcap`):

| Filter | Count |
|--------|------:|
| `sip.Method == REGISTER` | 32 |
| `sip.Status-Code == 403` | 6 |
| `sip.Status-Code == 401` | 0 |
| `sip.Method == INVITE` | 17 |
| `diameter` | 83 |
| `diameter.cmd.code == 257` (CER) | 4 |

---

## Gaps / blockers (cannot claim full E2E)

1. **No UE/RAN** — UERANSIM / srsRAN / COTS phone not exercised; cannot prove NAS P-CSCF delivery or IMS PDN from handset.
2. **Elisa compose image** — Infinispan `ResourceUtils` missing; host `dist/elisa` + `SF_CLUSTER_ISPN_ENABLED=false` used.
3. **Port 9090** — Open5GS `metrics` vs Elisa prometheus RA; stopped `metrics` for lab.
4. **UPF host-network** — vendor default broken on this host; bridged `UPF_IP` patch required.
5. **REGISTER 403 (fixed 2026-08-11)** — Root cause: SIPp first REGISTER carried
   `Authorization: … response=""`; S-CSCF treated that as a failed authenticated REGISTER → **403**
   instead of AKA **401**. Fix: `ScscfRegisterSbb.hasUsableAuthorization` ignores empty Digest
   response; SIPp scenario omits Authorization on first hop. Re-prove with provisioned `ims_subscriber`.
6. **INVITE unanswered** — no progress beyond REGISTER path this session.
7. **No Elisa↔Open5GS Diameter Cx** in this topology — Elisa uses HSS-lite; Open5GS PyHSS is for Kamailio IMS peers, not required for Gm SIPp.
8. **Rx/N5** — not Elisa ownership; PCRF up but no P-CSCF Rx from Elisa.

---

## What “fuller” would require next

1. Redeploy Elisa with empty-Authorization→401 fix; re-run SIPp `uac_register_challenge` → assert **401** (+ provisioned Ki/OPc)
2. Fix Elisa ISPN packaging (`ResourceUtils`) for compose image  
3. srsRAN ZMQ or UERANSIM attach → IMS APN → confirm PCO P-CSCF=`192.168.1.13` in NAS pcap  
4. UE SIP stack REGISTER to Elisa over IMS tunnel  
5. Optional later: Rx from a real P-CSCF (not Elisa today) into Open5GS PCRF  
