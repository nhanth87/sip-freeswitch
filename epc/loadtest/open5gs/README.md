# Open5GS MME lab topology — runbook (for our `epc` stack)

This brings up the open5gs **MME** as the real S6a client + S11 client that
attach-against our `epc-hss`, `epc-sgwc`, `epc-pgwc`. The MME is a standalone
control-plane daemon (`open5gs-mmed`) built from `loadtest/ims-tools/open5gs`.
It needs **no MongoDB** — subscriber auth vectors come from our `epc-hss` over
S6a (open5gs' `ogs_dbi_init` is only called by udr/hss/pcrf/pcf, never
`open5gs-mmed`).

Source read for this wiring: `loadtest/ims-tools/open5gs` — `src/mme/mme-fd-path.c`
(S6a client CER/AIR/ULR), `src/mme/mme-context.c` (required `s1ap`/`sgwc`/`pgw`
config keys), `configs/freeDiameter/mme.conf.in` (`ConnectPeer` = dial-out to HSS),
`configs/open5gs/mme.yaml.in`. Config/docker additions live in this
`epc/loadtest/open5gs/` directory.

## Files added/changed

| Path | What |
|------|------|
| `Dockerfile` | build `local/open5gs-mme:epc` (open5gs-mmed only) |
| `config/mme.yaml` | PLMN 001/01, TAC 1, gid 0010, `hss_map`→`epc-hss-1.epc.lab`, sgwc client `epc-sgwc`, smf client `epc-pgwc` |
| `config/mme.conf` | freeDiameter Identity `mme.epc.lab`, `ConnectPeer = "epc-hss-1.epc.lab" { ConnectTo="epc-hss"; No_TLS; }` |
| `docker-compose.services.yml` | `mme` service (profile `epc`) + commented optional `mme-sgwu`/`mme-pgwu` (profile `epc-up`) |
| `deploy/epc/roles/hss.env` | added `EPC_DIAMETER_ORIGIN_HOST`, `EPC_DIAMETER_DEST_HOST/REALM` |
| `deploy/epc/roles/sgwc.env` | `EPC_GTP_PEER_HOST=mme.host.invalid` → `mme` |

## Which host provides which plane

| Plane | Provider |
|-------|----------|
| **S6a** (AIR/AIA, ULR/ULA, PUR/PUA, CLR/IDR…) | `epc-hss` = Server (:3868); `mme` = **Client** (freeDiameter `ConnectPeer` dials out) |
| **S11** (Create/Modify/Delete Session) | `mme` = **Client**; `epc-sgwc` = Server (:2123) |
| **S5-C** | `epc-sgwc` (in-process `SgwControl`→`PgwControl`) ⇄ `epc-pgwc` — no wire |
| **Sxa / Sxb** (PFCP) | `epc-sgwc`⇄`sgwu` / `epc-pgwc`⇄`pgwu` — alpine placeholders (see below) |
| **S1-MME** (not used by smoke) | `mme` SCTP :36412 (no eNB in this runbook) |

`mme` S6a dials `epc-hss:3868`; our HSS (peer role=client) also dials
`mme:3868`. Either direction lands a CER→CEA, so `LinkStatusBoard` shows
`isPeerReady()` (CEA) — never a raw listen socket.

## Bring-up order

```bash
cd <repo root>
# 1) build the epc-host image + the standalone open5gs MME image
( cd epc && mvn -pl epc-host -am package )

# 2) full epc control-plane + the MME (alpine sgwu/pgwu placeholders stay default)
docker compose -f docker-compose.yml -f docker-compose.services.yml \
  --profile epc up -d --build

# 3) watch the S6a CER/CEA and S11 links come up
docker compose -f docker-compose.yml -f docker-compose.services.yml \
  --profile epc logs -f mme
docker compose logs epc-hss | grep -i -E 'cea|ready|peer'
```

`mme` `depends_on` `epc-hss` + `epc-sgwc` (started), so order is automatic.

## Smoke gates (control plane on the wire)

Resolve the sf-net container IPs from the host (compose user-defined bridge is
directly reachable from the host). Then run the existing scripts:

```bash
MME=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
       $(docker compose -f docker-compose.yml -f docker-compose.services.yml --profile epc ps -q mme))
HSS=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
       $(docker compose -f docker-compose.yml -f docker-compose.services.yml --profile epc ps -q epc-hss))

# (a) S6a — our HSS answers a Diameter CER with CEA Result-Code 2001 (S6a app 16777251)
DIAMETER_HOST="$HSS" DIAMETER_PORT=3868 DIAMETER_APP_ID=16777251 \
  ./epc/loadtest/scripts/diameter-cea-smoke.sh

# (b) S6a peer end-to-end: the MME's freeDiameter ConnectPeer reached our HSS.
#     (proven in mme/epc-hss logs: CER->CEA; smoke (a) alone only proves the HSS socket.)
docker compose logs mme     | grep -i -E 'cea|connected'
docker compose logs epc-hss | grep -i -E 'cea|ready|mme.epc.lab'

# (c) S11 — the MME answers a GTPv2-C Echo Request with an Echo Response (type 2)
GTP_ECHO_HOST="$MME" GTP_ECHO_PORT=2123 ./epc/loadtest/scripts/gtp-echo-smoke.sh

# (d) optional full acceptance gate (wire evidence wired into results/<date>-epc.json)
EPC_MME_HOST="$MME" EPC_DIAMETER_HOST="$HSS" \
  ./epc/loadtest/scripts/epc-acceptance.sh
```

Equivalent from *inside* sf-net (no host IP lookup):
`docker compose exec epc-hss sh -c 'getent hosts mme && getent hosts epc-hss'` then
run the python3 smoke steps against those names (the `mme` image ships python3).

### PCAP
```bash
sudo ./epc/loadtest/scripts/pcap-epc.sh capture     # then run the S6a/S11 smoke above
sudo ./epc/loadtest/scripts/pcap-epc.sh analyze pcaps/epc-<ts>.pcap
## Subscriber data — IMSI / APN (must match what our HSS stores)

**The HSS for this topology is our `epc-hss` (Postgres), not open5gs-hssd.**
The MME fetches every S6a subscriber from `epc-hss`, so the subscriber must be
provisioned in **our** HSS DB. Our HSS home PLMN is `PlmnId("001","01")` (MCC
001, MNC 01, 2-digit); subscribers live in `epc_subscriber` (schema
`epc/epc-hss/src/main/resources/db/migration-hss/V1__epc_subscriber.sql`) with
`mcc='001'`, `mnc='01'`, `imsi` prefix `00101`, `default_apn`, `apns_csv`. Ki/OPc
live out of band in `SecretStore` via `key_ref` — never SQL.

Seed a subscriber in our HSS, e.g.:

```sql
-- postgres-epc / epc DB (S6a SoT for the lab attach)
INSERT INTO epc_subscriber
  (imsi, msisdn, mcc, mnc, default_apn, apns_csv, qos_qci, key_ref, ue_ambr_ul_bps, ue_ambr_dl_bps)
VALUES
  ('001010000000001', '251900000001', '001', '01',
   'internet', 'internet', 9, 'lab-key-00101-000000001',
   1000000000, 1000000000);
```

The **open5gs Mongo subscriber JSON shown below is reference-only** (used when
running `open5gs-hssd` instead of our HSS). It documents the exact IMSI + APN
parity to keep if you ever seed open5gs-hssd; against THIS topology it is
**not** consumed (no Mongo, no open5gs-hssd).

```json
{
  "imsi": "001010000000001",
  "subscribed_rau_tau_timer": 12,
  "network_access_mode": 2,
  "subscriber_status": 0,
  "access_restriction_data": 32,
  "amf": "8000",
  "security": {
    "k": "465B5CE8B199B49FAA5F0A2EE238A6BC",
    "op": "E734F8734002D6B7C398BB99AAB3B057",
    "amf": "8000",
    "rand_val": "00000000000000000000000000000000",
    "sqn": 0
  },
  "apn_list": [
    {
      "apn": "internet",
      "pdn_type": 3,
      "qos": { "qci": 9, "arp": { "priority_level": 8, "pre_emption_capability": 1, "pre_emption_vulnerability": 1 } },
      "ambr": { "downlink": { "value": 1, "unit": 3 }, "uplink": { "value": 1, "unit": 3 } }
    }
  ],
  "ambr": { "downlink": { "value": 1, "unit": 3 }, "uplink": { "value": 1, "unit": 3 } }
}
```

## Known blockers (honest, from `epc/loadtest/open5gs/GAP-sgw-pgw.md`)

These were the original blockers; state on 2026-08-23:

1. ~~`ra-diameter` has no `setPeerHost` peer route~~ — **CLOSED.**
   `DiameterRaConfig.setPeerHost/setPeerPort` + Corsac outbound CER/CEA +
   `isPeerReady()` are installed; the HSS dials `mme:3868` and the MME's
   freeDiameter dials back. Unit-green only until the CER/CEA is seen here.
2. S5-C is still in-process by default (`SgwControl`→`PgwControl`); the wire
   split exists behind `EPC_S5C_WIRE=true` (not asserted against an external
   PGW-C here — the lab keeps SGW-C⇄PGW-C in one repo). Not claimed.
3. ~~`sgwu`/`pgwu` remain alpine placeholders~~ — **partially closed:** the
   `epc-up-linux` module programs PDR/FAR/QER/URR over PFCP + kernel GTP (no
   JVM packet path); the compose placeholders stay for the minimal smoke. Live
   PFCP Association + Session Establishment against the real UP still needs
   asserting on the wire.
4. No S1-MME eNB here (no UERANSIM) — this runbook proves S6a CER/CEA + S11
   Echo on the wire, **not** a full ATTACH. The attach PCAP closing `G3`
   stays open.

Run `docker compose config` after any change to validate the overlay. FreeSWITCH
/ Elisa services are untouched; `epc-cluster` never joins `elisa-cluster`.
```