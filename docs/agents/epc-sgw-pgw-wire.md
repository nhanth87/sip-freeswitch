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

## Open (seen on the same capture)

| # | Item |
|---|---|
| W-1 | **Gx blocks the SBB thread too**: CCA arrives in 405 ms, the PGW-C waits the full 2 s CCR timeout and falls back to the static decision — the PCRF's answer is ignored. Needs the same two-phase shape (CCR-I → CCA event → Sxb + S5-C response). |
| W-2 | **Gx CCR malformed**: Integer32 / Address AVPs sent as strings (flat map) — needs typed AVPs like the Cx fix. |
| W-3 | **`ra-pfcp` header not TS 29.244 §7.2.2**: Wireshark sees IE types 513/514/24576 — header off by bytes; our own UP decodes it with the same codec. Real IEs (Node ID, F-SEID, Create PDR/FAR/QER/URR) not checked yet. |
| W-4 | Modify Bearer and Release Access Bearers send a PFCP **Session Establishment**, not Session Modification. |
| W-5 | MME S11 / PGW S5-C F-TEIDs are in memory (`SgwControl.ControlPeers`, `PgwControl.S5cPeer`), not in `SessionCheckpoint` → lost on failover. |
| W-6 | Gy (OCS) not on the wire; dedicated bearers (Create/Update/Delete Bearer), Rx — later. |
