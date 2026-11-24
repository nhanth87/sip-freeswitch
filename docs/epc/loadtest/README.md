# EPC proof matrix — "prove it" harness (operator lab)

PRIVATE — `nhanth87/sip-freeswitch` only. This directory is the **only** allowed place for
EPC loadtest/acceptance tooling. Never modify `epc/src` or root `pom.xml` from here.

**Hard rule (from repo `AGENTS.md`):** green `mvn test` ≠ live EPC. Do not claim
attach / gigabit / HA / VoLTE without **wire** evidence. `LISTEN` ≠ live; live is a peer
answer on the wire:

| Plane | Live evidence (peer truth) | Not live |
|-------|----------------------------|----------|
| Diameter S6a/Cx | **CEA** (CER/CEA + DWR watchdog), `isPeerReady()` | socket listen / RA started |
| GTPv2-C S11/S5 | **Echo Response** (type 2) to an Echo Request (type 1) | port bound |
| PFCP Sxa/Sxb | **Association Setup Response** or **Heartbeat Response** | port bound |

---

## 1. Tool per plane (do not mix them up)

| Tool | Plane | Use it for | Do **not** use it for |
|------|-------|-----------|-----------------------|
| **SIPp** (`loadtest/ims-tools/sipp`) | SIP Gm/Mw/ISC | Elisa IMS CSCF REGISTER/INVITE CPS | EPC S6a / S11 / PFCP / attach |
| **open5gs MME** (`loadtest/ims-tools/open5gs`) | 3GPP MME | lab MME that originates S6a/S11 against `epc-hss`/`epc-sgwc` | SIP CSCF UAC |
| **UERANSIM** (lab install) | UE + gNB sim | S1AP/RRC attach peer in front of open5gs | — |
| **pyhss** (`loadtest/ims-tools/pyhss`) | Diameter HSS | scripted Cx/S6a **peer** (CER/CEA, UAR/MAR, AIR/AIA) | — |
| **scapy / tshark** | wire | PCAP capture + dissection of diameter/gtpv2/pfcp | — |
| **`gtp-echo-smoke.sh`** | GTPv2-C | minimal Echo Request → assert Echo Response | S6a |
| **`diameter-cea-smoke.sh`** | Diameter | minimal CER → assert CEA (Result-Code 2001) | GTP/PFCP |

**SIPp is for Elisa SIP only. A full wire attach requires the operator's lab
`eNB (UERANSIM) + MME (open5gs) + UE (UERANSIM)`** — it is not runnable from this sandbox.

---

## 2. Phase proof matrix

| Phase | Scope | Tool | Command | Expected wire evidence (PCAP filter) | Pass criterion |
|-------|-------|------|---------|---------------------------------------|----------------|
| **P0** datastore | HSS/AuC data, SQN atomicity | Maven (unit) | `mvn -B -ntp -pl epc-host -am test` | n/a (no wire) | surefire green; no JDBC/H2, PG only |
| **P0** artifact | ship only `dist/epc` | `package-dist-epc.sh` | `./tools/package-dist-epc.sh` | n/a | `dist/epc/quarkus-run.jar` mtime fresh; `dist/epc/run.sh` JDK-25 guard |
| **P1** S6a seam | Diameter peer live | `diameter-cea-smoke.sh` | `DIAMETER_HOST=<MME\|pyhss> DIAMETER_PORT=3868 ./scripts/diameter-cea-smoke.sh` | `diameter.cmd.code==257 && diameter.flags.request==0` | CEA seen; `Result-Code==2001` (DIAMETER_SUCCESS) |
| **P1** Cx seam | Elisa→HSS Cx | pyhss (peer) or `diameter-cea-smoke.sh` | `./loadtest/ims-tools/pyhss/...`, then UAR/MAR/SAR/LIR | `diameter` + `diameter.app_id==16777216` | Cx AVP path returns; `isPeerReady()` flips true (CEA) |
| **P2** HSS complete | AIR/AIA, ULR/ULA, PUR, CLR, IDR, DSR, RSR, NOR | pyhss / scripted S6a peer vs `epc-hss` | `diameter.app_id==16777251` (S6a); `diameter.cmd.code==318` (AIR), `==316` (ULR) | AIR→AIA AVPs (XRES/Kasme), ULR→ULA | AIR/AIA + ULR/ULA answers valid; SQN `UPDATE…RETURNING` |
| **P3** PCRF/Gx | PCEF→PCRF (and Gy/Rf) | scripted Gx peer + `ra-diameter` | Gx: `diameter.app_id==16777238`; Gy: `diameter.app_id==4` | CCR/CCA AVP path; RAR on change | Gx CCA authorized; fail-closed prepaid verified |
| **P3** PFCP | SGW-C/PGW-C → UP | open5gs SMF/UPF or real `sgwu`/`pgwu` | `pfcp.msg_type==AssociationSetupResponse \|\| pfcp.msg_type==HeartbeatResponse` | `udp port 8805` | PFCP Association/Heartbeat **Response** received |
| **P4** attach (wire) | S6a + S11 + S5 + PFCP E2E | **open5gs MME + UERANSIM (operator lab)** | run `pcap-epc.sh`, then attach UE | `s6a` AIR/AIA + ULR/ULA; `gtpv2` Create/Modify/Delete Session; PFCP Session Est; S1-U data on SGi | saved `pcaps/epc-attach-<ts>.pcap` shows full attach; UE reachable on SGi |

> P4 (full attach) and gigabit/HA/VoLTE are **explicitly NOT runnable in this sandbox** —
> they need the operator lab eNB+MME (open5gs MME + UERANSIM) and a real GTP-U user plane
> (`sgwu`/`pgwu`). See `docs/agents/epc-p1.md` for the open gaps (`G3` wire prove, `ra-diameter`
> peer route, UP placeholder).

---

## 3. Runner scripts in this directory

| Script | Purpose | Runs in sandbox? |
|--------|---------|------------------|
| `scripts/epc-acceptance.sh` | Non-interactive gate runner: JDK 25 → `mvn test` → `package-dist-epc.sh` → optional wire smoke → writes `results/<date>-epc.json` | Yes (wire gates auto-**skip** unless peer env set) |
| `scripts/pcap-epc.sh` | tshark capture on 3868 (S6a/Cx), 2123 (S11), 8805 (PFCP), optional 5060 (SIP) | Yes (needs root + traffic) |
| `scripts/diameter-cea-smoke.sh` | Minimal Diameter CER → assert CEA | **Lab-only** (needs real Diameter peer) |
| `scripts/gtp-echo-smoke.sh` | Minimal GTPv2-C Echo Request → assert Echo Response | **Lab-only** (needs real GTP-C peer) |

### Acceptance run

```bash
# Full local gate (no wire peer → wire gates = skip, wire_proof=false):
./epc/loadtest/scripts/epc-acceptance.sh

# With operator-lab peers set (wire gates attempted and asserted):
EPC_MME_HOST=10.20.0.10 EPC_MME_PORT=2123 \
EPC_DIAMETER_HOST=10.20.0.11 EPC_DIAMETER_PORT=3868 \
./epc/loadtest/scripts/epc-acceptance.sh
```

### PCAP

```bash
# Capture EPC control plane to pcaps/epc-<ts>.pcap (default iface=any)
sudo ../scripts/pcap-epc.sh capture

# Analyze an existing capture with per-plane display filters
../scripts/pcap-epc.sh analyze ../pcaps/epc-attach-20260819.pcap

# Include the Elisa SIP plane in the capture (for IMS overlay work):
PCAP_SIP=1 sudo ../scripts/pcap-epc.sh capture
```

### Wire smoke (lab only)

```bash
# Diameter CEA proof against a peer (open5gs MME S6a / pyhss / DRA)
DIAMETER_HOST=10.20.0.11 DIAMETER_PORT=3868 ./scripts/diameter-cea-smoke.sh

# GTP Echo proof against an MME (S11)
GTP_ECHO_HOST=10.20.0.10 GTP_ECHO_PORT=2123 ./scripts/gtp-echo-smoke.sh

# Probe the local epc-host S11 / S6a listener (still LISTEN-as-evidence, NOT peer-live):
GTP_ECHO_HOST=127.0.0.1 GTP_ECHO_PORT=2123 ./scripts/gtp-echo-smoke.sh
DIAMETER_HOST=127.0.0.1 DIAMETER_PORT=3868 ./scripts/diameter-cea-smoke.sh
```

---

## 4. Ports & env (matches `epc-host` config)

| Plane | Port | Env (peer) | Env (bind) |
|-------|------|-----------|------------|
| Diameter S6a/Cx | 3868/tcp | *(none yet — Phase-0 peer route gap)* | n/a (`newDiameterEndpoint` binds 0.0.0.0:3868) |
| GTPv2-C S11/S5 | 2123/udp | `EPC_GTP_PEER_HOST` / `EPC_GTP_PEER_PORT` | `epc.gtp.bind.port` (default 2123) |
| PFCP Sxa/Sxb | 8805/udp | `EPC_PFCP_PEER_HOST` / `EPC_PFCP_PEER_PORT` | `epc.pfcp.bind.port` (default 8805) |
| Admin/status | 8099/tcp | — | `EPC_HTTP_PORT` |

Status truth: `GET http://127.0.0.1:8099/epc/status` → `links.{s6a,gtp,pfcp}.live`
(peer evidence only). `localListen` true + `live` false = listening but **not live**.