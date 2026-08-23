# EPC Production P1 — plan (epc-hss / epc-pcef / epc-pgwc / epc-sgwc)

**Status:** PLAN only (2026-08-18). No code changed. Read `docs/agents/epc.md`, `epc/docs/agents/*`, `epc/AGENTS.md`, `docs/3gpp/*`, `docs/agents/production p0.p1` before implementing.

**Target:** first operator-usable slice. Gate = **a real MME attach on the wire** — S6a AIR/ULR → HSS; S11 Create/Modify/Delete Session; S5-C; PFCP Sxa/Sxb; S1-U/S5-U/SGi Linux GTP forwarding; UE reachable on SGi; a charging CDR reconciled. Unit tests ≠ wire prove ≠ attach PCAP ≠ gigabit. Per `epc/AGENTS.md`: never claim attach/HA/VoLTE without wire proof + soak + charging reconciliation.

---

## 1. Current state (grounded)

**One `epc-host` image → N services by role flag** (`EPC_ROLE_HSS/SGWC/PGWC`). PCEF is a **library** (`epc-pcef`), embedded in `epc-pgwc` (`EpcRoles.wirePcef() == pgwc`), not a separate service.

| Service | Role flag | Planes | Deploy role file |
|---------|-----------|--------|------------------|
| `epc-hss` | `EPC_ROLE_HSS` | S6a + Cx (dedicated `postgres-epc`) | `deploy/epc/roles/hss.env` |
| `epc-sgwc` ×N | `EPC_ROLE_SGWC` | S11/S5 GTPv2-C + Sxa PFCP | `sgwc.env`, `sgwc-2.env` |
| `epc-pgwc` ×N | `EPC_ROLE_PGWC` | S5 + Sxb + PCEF + IPAM | `pgwc.env` |
| `sgwu`/`pgwu` | non-JVM | Linux GTP baseline (1:1 with C node) | compose `sgwu`, `pgwu`, `sgwu-2` |

**Already implemented (unit/code, honest):**

- `ra-gtpv2c`: Echo T=0 (TS 29.274 §5.1), Version Not Supported (type 3), grouped Bearer Context IE 93 (EBI+F-TEID), T3/N3 retransmit, duplicate response replay (§7.6). Peer live = Echo **Response**.
- S6a (`HssService` + `JdbcHssRepository`): AIR/AIA (EPS-AKA vectors incl. AUTS resync), ULR/ULA, CLR/CLA, PUR/PUA, IDR/IDA, DSR/DSA, RSR/RSA, NOR/NOA — all PICS-M present. SQN via `UPDATE … RETURNING` (atomic, no shared vector). Ki/OPc stay out of SQL (`key_ref` only → `SecretStore`).
- `epc-crypto`: `EpsAka`, `Milenage` (TS 35.208 test), `Kasme`.
- GTP-C procedures in `GtpGatewaySbb`: Create/Modify/Delete Session, RAB 171, DDN 177 ack (unit-tested `EpcSleeLayeringTest`).
- `SgwControl`/`PgwControl`: S11/S5 session, Sxa/Sxb PFCP `PfcpProgramSession`, IPAM, PCEF open/authorize/report/close/reconcile.
- Clustering: `epc-cluster` (never `elisa-cluster`), TEID high-byte = `EPC_CLUSTER_NODE_INDEX`, `DIST_SYNC` on sgwc/pgwc.

**Hard blockers (do not hide):**

1. **No wire prove — `G3` open.** "Attach / PDN connectivity PCAP — Open. Unit tests ≠ MME on the wire. Not GTP attach GO." No S6a+S11 attach against a real MME; `sgwu`/`pgwu` are alpine placeholders.
2. **`ra-diameter` peer seam landed + Corsac boot crash fixed (2026-08-23).** `setPeerHost/setPeerPort` exist and the Corsac transport dials + exchanges CER/CEA; the per-app registration crash (`The request 271 is already registered for application 0`) is fixed in `CorsacDiameterTransport` (see [`epc-hss-pcrf-full3gpp.md`](epc-hss-pcrf-full3gpp.md) §1.1). Stack now boots all four apps. **What remains open is wire prove** — no live peer has answered S6a-to-real-MME / Elisa Cx→epc-hss yet.
3. **UP is a placeholder.** `sgwu`/`pgwu` = `alpine sleep`; `epc-up-linux/LinuxGtpAgent` exists but no real kernel-GTP container, no S1-U/S5-U/SGi.
4. **PCEF static / fail-closed in-memory.** `ocsLive`/`pcrfLive` flags only; no Gy/Gx/Rf wire; CDR heap-only; reconciliation heap-only.
5. **No PGW-C HA node** (`pgwc-2` absent), no `pcef` role separation (out of scope for P1 — keep co-located).
6. **No results gate.** `epc/loadtest/scripts/pilot-acceptance.sh` fails closed (no `results/<date>.json`).

---

## 2. Phases

### Phase 0 — Fix the Diameter peer seam (blocker, do first)
- `ra-diameter` (or thin `DiameterPeerPort`): add outbound **peer config** (`setPeerHost/port/realm`), initiate CER, route answer/request to the configured peer. Highest-leverage item — unblocks S6a *and* Elisa Cx→epc-hss.
- Wire from env (`EPC_S6A_PEER_HOST=MME`, Cx dest). `LinkStatusBoard` must show `isPeerReady()` (CEA) only, never listen-as-up.
- **Exit:** a real Diameter peer gets CEA; `S6aPeerWatch`/`LinkStatusBoard` flips to live.

### Phase 1 — Real Linux GTP-U (replace placeholders)
- Build a real `epc-up-linux` container (kernel `gtp` module or bridge over `LinuxGtpAgent`) for `sgwu`/`pgwu` (1:1 with C node).
- PFCP from `SgwControl`/`PgwControl` programs PDR/FAR/QER/URR on the real UP; S1-U/S5-U/SGi forwarding; optional SGi NAT for pilot.
- **Exit:** `epc/loadtest/bakeoff` writes `results/<date>.json` with a real data flow (ping/iperf) across SGi, full-duplex, IPv4+IPv6.

### Phase 2 — Wire attach (S6a + S11 + S5 + PFCP E2E)
- Use in-tree `loadtest/ims-tools/open5gs` **MME** as the test MME: S6a→epc-hss (DRA-sim or direct), S11→epc-sgwc.
- Capture: AIR/AIA + ULR/ULA, Create Session Rsp (IP alloc), Modify Bearer, PFCP Session Est on Sxa+Sxb, S1-U data.
- **Exit:** `epc/loadtest/pcaps/epc-attach-<ts>.pcap` saved — closes `G3`.

### Phase 3 — PCEF to a real charging surface (P1 charging)
- Keep PCEF co-located in `epc-pgwc` (no new service for P1).
- Wire Gx→PCRF (or static-deny) and Gy/Rf ACR→OCS/OFCS when `peerReady`; keep fail-closed for prepaid, fail-open postpaid offline (matches Elisa charging rules).
- CDR: heap → PG + reconcile with usage (no double-debit), claim-by-charging-id.
- **Exit:** INVITE/datanet CDR on the wire; reconcile clean; no double-debit on recovery.

### Phase 4 — HA + operator hardening
- Add `pgwc-2` + `sgwu-2` pairing; `DIST_SYNC` on `epc-cluster`; TEID high-byte node index.
- Prove process-kill recovery (SGW-C/PGW-C) with session restore from checkpoint.
- `./tools/package-dist-epc.sh` → `dist/epc/`, restart, `:8099` status, jar mtime — the "prove the artifact" gate (`epc/AGENTS.md`).
- **Exit:** kill a C node mid-session; peer continues + session restored; `:8099` shows truth.

### Phase 5 — Acceptance (no silent claims)
- `pilot-acceptance.sh` writes `results/<date>.json` (10k contexts, full-duplex 1–2 Gbit/s, IPv4+IPv6, mobility, charging reconcile, kill recovery) — else fail closed.
- **Explicit non-claims:** no VoLTE QoS (Rx out), no 10 Gbit/s on Java, no HA without kill-prove, no NextEPC 30G as 4G proof, no attach GO from unit tests.

---

## 3. Execution order (recommended)

1. **Phase 0** — Diameter peer seam (`setPeerHost`/peer route). Unblocks everything.
2. **Phase 1** — Real Linux GTP-U for `sgwu`/`pgwu`.
3. **Phase 2** — In-tree open5gs MME wire-attach PCAP (closes G3).
4. **Phase 3** — PCEF Gx/Gy + PG CDR.
5. **Phase 4** — `pgwc-2` HA + kill-recovery + `package-dist-epc` + `pilot-acceptance` results file.
6. **Phase 5** — acceptance run + honest report.

---

## 4. Constraints (unchanged, locked)

- Java 25 only (`zulu-25`). Control plane Java; **user plane not JVM** (Linux GTP / VPP / optional NextEPC).
- Diameter S6a/Cx/Gx/Gy → vendor `ra-diameter` only; no second Diameter stack. SIP → Elisa `sip-servlet-ra` only; `epc-host` bans `ra-sip-servlet`.
- `epc-cluster` must never join `elisa-cluster`. Dedicated `postgres-epc`/`epc` — never `elisa`/`ussdgw`. Never H2.
- RAs live in micro-jainslee `vendor-ras/` (`ra-gtpv2c`, `ra-pfcp`, `ra-diameter`); procedures in this tree. Depend on nhanth87 RA artifacts only.
- Never vendor Mobius GTP (AGPL). No GTP-U/S11/S6a/PFCP/PCEF inside the Elisa JVM.
- Commit/push: nhanth87 / Tran Nhan only. Never rsync `dist/epc/` to a host without operator ask. `dist/epc/` ship unit only.
- P1 is not production charging / not VoLTE / not 10 Gbit/s / not HA until each gate is proven.

---

## 5. Lab wiring — open5gs MME (epc profile)

Full runbook: **`epc/loadtest/open5gs/README.md`**. This wires the open5gs MME
(standalone `open5gs-mmed`, built in-tree) into the `epc` profile as our real
S6a **client** (freeDiameter `ConnectPeer` → `epc-hss:3868`) and S11 **client**
(gtpc client → `epc-sgwc:2123`). No MongoDB needed (only open5gs udr/hss/pcrf/pcf
call `ogs_dbi_init`; never `open5gs-mmed`).

Bring-up:
```bash
(cd epc && mvn -pl epc-host -am package)
docker compose -f docker-compose.yml -f docker-compose.services.yml --profile epc up -d --build
```

Wire smoke (host → sf-net container IPs, resolved by `docker inspect` — see README):
```bash
DIAMETER_HOST=$HSS DIAMETER_PORT=3868 DIAMETER_APP_ID=16777251 ./epc/loadtest/scripts/diameter-cea-smoke.sh  # S6a CEA 2001
GTP_ECHO_HOST=$MME  GTP_ECHO_PORT=2123                       ./epc/loadtest/scripts/gtp-echo-smoke.sh         # S11 Echo Rsp
EPC_MME_HOST=$MME EPC_DIAMETER_HOST=$HSS                     ./epc/loadtest/scripts/epc-acceptance.sh          # results/<date>-epc.json
```

New/changed artifacts:
- `docker-compose.services.yml`: added `mme` (profile `epc`; build `local/open5gs-mme:epc`
  from `epc/loadtest/open5gs/Dockerfile`, mounts `config/mme.yaml` + `config/mme.conf`).
  Optional real UP `mme-sgwu`/`mme-pgwu` commented out under profile `epc-up`.
- `epc/loadtest/open5gs/`: `Dockerfile`, `config/mme.yaml` (PLMN 001/01, TAC 1,
  gid 0010, `hss_map`→`epc-hss-1.epc.lab`, sgwc client `epc-sgwc`), `config/mme.conf`
  (Identity `mme.epc.lab`, `ConnectPeer="epc-hss-1.epc.lab"{ConnectTo="epc-hss";No_TLS;}`), `README.md`.
- `deploy/epc/roles/hss.env`: `EPC_DIAMETER_ORIGIN_HOST=epc-hss-1.epc.lab`,
  `EPC_DIAMETER_DEST_HOST=mme.epc.lab`, `EPC_DIAMETER_DEST_REALM=epc.lab`.
- `deploy/epc/roles/sgwc.env`: `EPC_GTP_PEER_HOST=mme` (was `mme.host.invalid`).

**Honest non-claims:** this proves S6a CER/CEA + S11 Echo on the wire, NOT a full
attach (S1-MME/UE absent), NOT GTP-U (alpine placeholders remain), NOT external
S5-C (in-process), NOT the `ra-diameter` `setPeerHost` Phase-0 seam. Attach PCAP
closing `G3` stays open. See `GAP-sgw-pgw.md` blockers.
