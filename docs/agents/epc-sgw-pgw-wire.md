# SGW-C / PGW-C on the wire — S11, S5-C, Gx, Sxa/Sxb (2026-11-24)

Every node is micro-jainslee: `gtpv2c-ra` / `pfcp-ra` / `diameter-ra` carry the wire, the SBBs
(`GtpGatewaySbb`, `PcefSbb`, `UpfPfcpSbb`) run the procedures, `SgwControl` / `PgwControl` /
`PcefService` are plain services. Related: [`epc-p1.md`](epc-p1.md),
[`../epc/loadtest/open5gs/GAP-sgw-pgw.md`](../epc/loadtest/open5gs/GAP-sgw-pgw.md).

```text
 lab MME (host 172.20.0.1, loadtest/gtp/MmeS11.java)
     │ S11 GTPv2-C
     ▼
 epc-sgwc .14 ──S5-C GTPv2-C──▶ epc-pgwc .13 ──Gx Diameter (CCR/CCA)──▶ epc-pcrf .12
     │ Sxa PFCP                     │ Sxb PFCP
     └────────────▶ epc-up-linux .16 ◀┘
```

## Run

```bash
(cd epc && mvn -o -pl epc-host -am package -DskipTests)
DC="docker compose -f docker-compose.yml -f docker-compose.services.yml -f docker-compose.epc-wire.yml --profile epc"
$DC build epc-sgwc epc-pgwc epc-pcrf epc-up-linux
$DC up -d postgres-epc epc-sgwc epc-pgwc epc-pcrf epc-up-linux
# capture on the sf-net bridge (br-<network id>), then:
(cd epc && mvn -o -q -pl epc-sgwc dependency:build-classpath -Dmdep.outputFile=/tmp/cp)
java -cp "$(cat /tmp/cp)" loadtest/gtp/MmeS11.java 172.20.0.1 172.20.10.14 001010000000009 internet
```

`MmeS11` sends Create Session → Modify Bearer (eNB S1-U) → Delete Session and prints every IE.

## Result (2026-11-24, `loadtest/pcaps/epc/s11-s5-gx-pfcp-attach.pcap`)

| Step | Wire | Result |
|---|---|---|
| S11 Create Session | MME → SGW-C → S5-C → PGW-C → Gx CCR-I/CCA → Sxb Establishment → S5-C Response → Sxa Establishment → S11 Response | Cause 16, header TEID = MME S11 TEID; Sender F-TEID SGW S11 (if 11), PGW S5/S8-C (if 7, inst 1), PAA 10.64.0.2, Bearer Context {EBI 5, Cause 16, S1-U SGW (if 1), S5/S8-U PGW (if 5, inst 2)} |
| S11 Modify Bearer | eNB S1-U → SGW-C → Sxa | Cause 16, Bearer Context {EBI, Cause, S1-U SGW} |
| S11 Delete Session | SGW-C → S5-C Delete → PGW-C → Sxb Deletion → S5-C Response → Sxa Deletion → S11 Response | Cause 16 |

## Fixed to get there

1. **`ra-gtpv2c` (micro-jainslee)**: RA Echo and SBB requests both started at sequence 1, and the
   response cache keyed on (peer, sequence) only — the PGW-C answered the S5-C Create Session
   Request with its cached **Echo Response**. Echo now uses the top half of the sequence space and
   the cache / in-flight key includes the procedure. New `GtpProcedureTimeoutEvent` on T3 × N3.
2. **Addresses**: every F-TEID advertised `127.0.0.1` and the Sxa/Sxb PFCP peer was hard-coded to
   `127.0.0.1:8805` (`epc.pfcp.peer.*` was read only for display). New `epc.local.address`
   (`EPC_LOCAL_ADDRESS`, default = first non-loopback IPv4); the PFCP peer comes from `epc.pfcp.peer.*`.
3. **S5-C blocked the SBB thread** (`CompletableFuture.get(5 s)`) waiting for a response that is
   itself a SLEE event → always 5 s then cause 73. `SgwControl` is now two-phase:
   `beginCreateSession` → `onS5cCreateResponse` / `onS5cTimeout`; same for Delete.
4. **TS 29.274 shapes**: S5-C request carried the S5-**U** F-TEID as Sender F-TEID (if 4) and no
   Bearer Context; response header TEID echoed the request's (0) instead of the peer's Sender F-TEID
   TEID (§5.5.2); interface types wrong (S11 SGW was 10 = MME, S1-U SGW was 0 = eNB, PGW S5-C was
   6 = SGW). Shapes live in `GtpcShapes` (Tables 7.2.1-1 / 7.2.2-1 / 7.2.8-1, §8.22).
5. **SGW-U rules**: Create Session programmed S1-U ↔ the MME's **S11** F-TEID. Now uplink
   S1-U(SGW) → S5-U(PGW), downlink S5-U(SGW) → S1-U(eNB) once Modify Bearer brings the eNB.
6. **S5-C Delete Session** did not exist on the wire (SGW-C deleted its in-process PGW copy);
   PGW-C deleted no Sxb session. Both now delete PFCP sessions.
7. **Postgres**: `JdbcCdrStore` wrote `cdr_kind`, no migration added it → every PGW-C Create
   Session failed (`pcef cdr persist`). `V9__epc_pcef_cdr_kind.sql`.

## Round 2 (2026-11-25) — W-1..W-5 closed, wire-proven

Captures: `loadtest/pcaps/epc/epc-w1-w4-cold.pcap`, `epc-w1-w4-warm.pcap` (gitignored). Lab MME now
runs Create → Modify Bearer → Release Access Bearers → Modify Bearer (new eNB TEID) → Delete.

| Check | Result |
|---|---|
| S11 | 5 × cause 16; every response header TEID = MME S11 TEID (RAB fixed too) |
| Create latency | 804 ms cold (first CCA of a cold PCRF), 219 ms warm — was a fixed 5 s / 2 s wait |
| Gx | CCR-I / CCA 2001, CCR-T / CCA 2001; PGW-C uses the PCRF decision (`PgwS5cProcedure Gx CCA … qci=9`) |
| PFCP | 7 / 7 responses cause 1 (2 Establishment, 3 Modification, 2 Deletion) |
| UP tunnels | 0→1 (DL buffered), 1→2 (Modify), 2→1 (RAB), 1→2 (Modify) |
| Wireshark | 0 malformed, 0 expert errors (GTPv2, PFCP, Diameter) |

| # | Fix |
|---|---|
| W-1 | Gx two-phase (`epc-host/.../sbb/PgwS5cProcedure`): S5-C Create → allocate + CCR-I, return; CCA event → Sxb + S5-C response with the PCRF QoS (Bearer QoS, APN-AMBR); `DiameterTimeoutEvent` (new ra-diameter Tx timer) → local policy; Result-Code ≠ 2001 → cause 73 + release. CCR-T on Delete Session. Gx Session-Id derived from Origin-Host + Charging-Id (any node can send the CCR-T). |
| W-2 | Typed CCR / CCA (`et.epc.api.diameter.GxAvp`). Wrong codes fixed: Default-EPS-Bearer-QoS 1049 (was 1023), Precedence 1010, Max-Requested-Bandwidth-DL 515 (also S6a AMBR), GBR UL/DL 1026/1025, APN-AMBR-DL 1040. Enum values: Pre-emption 0 = ENABLED, Flow-Status 2 = ENABLED. PCRF answers 5030 when no IMSI. ra-diameter: typed inbound leaves + the Corsac path now carries the full typed tree. |
| W-3 | ra-pfcp TS 29.244 (header, 2-octet IE type, flag bits, real Create PDR/FAR/QER/URR, UP answers with Node ID / Cause / UP F-SEID). |
| W-4 | Sxa rules = UL PDR/FAR 1 + DL PDR/FAR 2 (BUFF\|NOCP until Modify Bearer); Modify Bearer / RAB = Session Modification with Update FAR 2. Sxb = anchor rules (UL F-TEID → SGi, DL UE IP → GTP-U to the SGW). UP keeps `PfcpSessionState` in a table shared by every pooled `UpfPfcpSbb` entity (a Modification landing on another entity answered cause 65 before). |
| W-5 | `SessionCheckpoint` carries the GTP-C peers (MME S11, SGW / PGW S5-C, PGW-C address, PGW S5-C TEID); `epc-session-index` ISPN cache + Postgres V10 for TEID lookups; SgwControl / PgwControl maps are caches over the shared store. |
| ra-gtpv2c | Response cache kept only T3 × (N3 + 1) (§7.6) — it grew forever and replayed old responses for a reused sequence. |

## Clustering (micro-jainslee, epc-cluster)

`EPC_CLUSTER_ISPN_MODE=DIST_SYNC`: `ClusterManager` adopted from the EPC Infinispan manager,
`DistributedSbbEntityPool`, session ownership in each RA (`ra-gtpv2c-owner` by TEID, `ra-pfcp-owner` by SEID,
`ra-diameter-owner` by Session-Id) with take-over of a departed node's sessions
(`RaHaSupport.claimOnInbound`). GTP restart counter = `EPC_GTP_RECOVERY` (one value per service address).
Elisa's Cx Diameter RA now joins `elisa-cluster` like its SIP RA. AuC: SQN is never served from the cache
(`UPDATE … RETURNING` / `GREATEST` on Postgres). Two-node kill test: pending (see below).

## Open

| # | Item |
|---|---|
| C-5 | Two-node SGW-C / PGW-C kill test (DIST_SYNC) and two-node HSS concurrent AIR/SAI (SQN uniqueness) — wire proof pending. |
| W-6 | Gy (OCS), dedicated bearers (Create/Update/Delete Bearer), Rx. |
| I-1 | Interop: open5gs MME / PCRF / UPF and free5gc UPF against these nodes — pending. |
| P-1 | In-flight procedures (pending S5-C / Gx) are node-local; a node dying mid-procedure relies on the peer's retransmission. PFCP association is per CP node: a UP that enforces association ownership needs SMF-set style handling on failover. |
