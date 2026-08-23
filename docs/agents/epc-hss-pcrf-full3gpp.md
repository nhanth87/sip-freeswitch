# EPC — HSS + PCRF hoàn chỉnh 3GPP (Postgres SoT + Infinispan cache) — plan

**Status:** PLAN (2026-08-23). Audit grounded in code read of `epc-hss`, `epc-pcrf`,
`epc-pgwc`, `epc-sgwc`, `epc-pcef`, `epc-host` (all in-tree). Read first:
[`epc-p1.md`](epc-p1.md) · [`epc.md`](epc.md) · [`epc-slee.md`](epc-slee.md) ·
`epc/AGENTS.md` · `epc/docs/3gpp/*`. Unit tests ≠ wire prove ≠ attach PCAP.

**Request (2026-08-23):**
1. Re-audit `epc-hss` / `epc-pcef` / `epc-pgwc` / `epc-sgwc` against production P1.
2. Plan: implement `epc-hss` + `epc-pcrf` as **complete 3GPP HSS / PCRF** with
   **Postgres** as the durable user store and **Infinispan** as the cache.

---

## 1. P1 audit — honest, code-grounded

### 1.1 Fixed in this session (unblocks everything)

`ra-diameter` had a hard boot crash: `DiameterParser: The request 271 is already
registered for application 0` — the RA re-registered the common commands (CER/CEA,
already registered by the stack constructor) and passed one auth-application list
with the commons package. Fixed in `CorsacDiameterTransport`: per-application
registration (S6a 16777251 / CxDx 16777216 / Gx 16777238 / CreditControl 4), impl
package registered first, packages taken from loaded classes (no
`Package.getPackage(String)` null trap). Regression test:
`DiameterCorsacStackBootTest` (real loopback stack boot, both endpoints, all four
apps) — green, 16/16 module tests pass. **`epc-p1.md` blocker #2 (peer seam) is now
fully unblocked at the stack level; wire prove still open.**

### 1.2 `epc-hss` — ✅ P1-ready on the control plane

| 3GPP surface | State | Evidence |
|---|---|---|
| S6a (TS 29.272) all 16 commands | ✅ | `HssService.handle()`: AIR/AIA, ULR/ULA, CLR/CLA, PUR/PUA, IDR/IDA, DSR/DSA, RSR/RSA, NOR/NOA |
| EPS-AKA vectors (TS 33.401) | ✅ | `epc-crypto` `EpsAka`/`Milenage` (TS 35.208 test vectors), AUTS resync (`HssServiceResyncTest`) |
| SQN atomicity | ✅ | `UPDATE … RETURNING` in `JdbcHssRepository` — never heap, never shared vectors |
| Ki/OPc out of SQL | ✅ | `JdbcSecretStore` via `key_ref` (TS 33.102 posture) |
| Cx (TS 29.228) for IMS | ✅ | `CxService`: UAR/MAR/SAR/LIR/RTR/PPR + push RTR/PPR; S-CSCF assignment |
| Postgres SoT | ✅ | `epc_subscriber`, `epc_secret`, `epc_ims_identity`, `epc_ims_ifc` (migrations V1–V3, `postgres-epc`) |
| Infinispan cache | ✅ | `CachingHssRepository` read-through + invalidation on every mutation + boot `warm()` (wired in `EpcBootstrap`) |
| Tests | ✅ | 18 test classes incl. security / structured-AVP / resync |

**P1 gaps (not code, gates):** wire prove vs real MME (G3 open); MME allowlist
provisioning; no Sh/Lg (out of P1 scope).

### 1.3 `epc-pcrf` — ⚠️ core present, wire seam missing

| 3GPP surface | State | Evidence |
|---|---|---|
| Gx CCR/CCA (TS 29.212) | ✅ | `GxHandler`: Initial/Update/Termination, Charging-Rule-Install/Remove, Default-EPS-Bearer-QoS, Usage-Monitoring-Information, 3GPP vendor grouping (10415) |
| Rx (TS 29.214) | ✅ | `RxHandler`: AA-R/STR parse; `PcrfSbb` ASR/RAR push |
| Policy store | ✅ | `JdbcPolicyRepository`: `epc_pcc_rule`, `epc_ipcan_session`, `epc_pcrf_af_session` (migration V4) |
| Cache | ✅ | `IspnPccCache` (Infinispan read-through over the durable rules) |
| Role flag | ✅ | `EpcRoles.pcrf` exists; `DiameterGatewaySbb` demuxes Gx/Rx → `PcrfSbb` |
| **Gx wire PCEF↔PCRF** | ❌ | today in-process inside `epc-pgwc` (`pcrfLive` flag); no standalone PCRF Diameter endpoint exercised on the wire |
| RAR push on admin policy change | ❌ | push helpers exist, no trigger wired |

### 1.4 `epc-pgwc` — ⚠️ control plane ready; wire/UP/HA open

S5-C session + Sxb PFCP (`PfcpProgramSession`) + IPAM + embedded PCEF
(open/authorize/report/close/reconcile) + URR→CDR (`JdbcUrrDatastore`, V5,
restart-safe baseline). **Gaps:** `pgwc-2` HA node; SGi reachability (real UP);
external S5-C peer.

### 1.5 `epc-sgwc` — ⚠️ control plane ready; wire/UP open

S11 Create/Modify/Delete + RAB 171 + DDN 177 ack, Sxa PFCP, TEID high byte =
`EPC_CLUSTER_NODE_INDEX`, dual-write DIST_SYNC on `epc-cluster`. **Gaps:** attach
wire prove against real MME; `sgwu` is alpine placeholder.

### 1.6 `epc-pcef` — ⚠️ library, co-located in PGW-C

PCEF is a library (`epc-pcef`), wired into PGW-C per `epc-p1.md` §1 — **not** a
separate service in P1. `ocsLive`/`pcrfLive` are honest fail-closed flags; no
Gy/Rf wire yet (P1 declares no online charging).

**Audit verdict:** control planes of all four modules are P1-coded and unit-green;
the P1 *gates* (G3 attach PCAP, real UP, results file, HA) remain open exactly as
`epc-p1.md` states. Nothing here downgrades that.

---

## 2. Plan — complete 3GPP HSS + PCRF (Postgres + Infinispan)

Both data planes the user asked for are **already Postgres-soT + ISPN-cached** in
skeleton (`CachingHssRepository`, `IspnPccCache`). The plan below completes them to
3GPP and closes the seams — it does **not** rewrite working code.

### Phase A — HSS data-model completeness (TS 23.008 §2.3 / TS 29.272)

**Verified 2026-08-23 (code read, not assumed):** `HssService.subscriptionDataStructured`
already emits the full 3GPP **binary grouped** Subscription-Data on the ULA:
grouped 1400 → Subscriber-Status 1424, MSISDN 701 (TBCD), Network-Access-Mode 1417,
Operator-Determined-Barring 1425, Access-Restriction-Data 1426, grouped UE-AMBR
1435 → 516/517, grouped APN-Configuration-Profile 1429 → Context-Identifier 1427 +
All-APN-Configurations-Included-Indicator 1428 + per-APN grouped 1430, and
Subscribed-Periodic-RAU-TAU-Timer 1439. So A.1/A.2 below are **wire-format done**;
what remains is data completeness + policy.

1. **APN configuration profile table** `epc_apn_profile`
   (`imsi, apn, pdn_type, qci, arp_level/cap/vuln, apn_ambr_ul/dl, mbr_ul/dl,
   gbr_ul/dl, charging_characteristics, default_flag`) — today APNs live inside the
   `epc_subscriber` profile JSON; a relational table makes admin/provisioning real.
2. **Subscription completeness on `epc_subscriber`**: APN-OI-Replacement, MIP6
   capability flags (PICS-O optional — default off). UE-AMBR / operator-determined
   barring already carried.
3. **Vectors policy (TS 33.102)**: honor `Number-Of-Requested-Vectors` (1–5),
   vector-separation flag, IND/SEQ SQN structure review of `EpsAka`, resync window
   bounds (reject AUTS outside Δ). Secrets never in cache — keep `SecretStore`.
4. Admin seed path: extend `epc-seed-subscriber.sh` to insert APN profiles.

### Phase B — Cx completeness for IMS (TS 29.228 / TS 33.203)

1. MAR digest: ensure `SIP-Authentication-Scheme` supports **AKAv1-MD5** digest
   (S-CSCF pulls vectors for IMS AKA) — verify `CxService.mar()` output against
   TS 33.203 §6.1.2; confirm the E-UTRAN vector path is not reused for IMS.
2. IMPU registration state machine: `NOT_REGISTERED / REGISTERED / UNREGISTERED`
   persisted on `epc_ims_identity` (column + transitions in SAR/RTR/PPR).
3. IFC download in SAR/PPR from `epc_ims_ifc`; Server-Capabilities honored in UAR
   (already partial — close the gap).

### Phase C — PCRF completeness (TS 29.212 / TS 29.214 / TS 23.203)

1. **Standalone Gx wire**: deploy role file `deploy/epc/roles/pcrf.env`
   (`EPC_ROLE_PCRF`, own `originHost=epc-pcrf-1.epc.lab`, `:3868`) so PGW-C/PCEF
   dials it as a real Diameter peer (`isPeerReady()` truth).
2. **PCEF→PCRF wire**: `epc-pgwc` switches `PcrfClient` from in-process to the
   PCRF peer; `pcrfLive=true` only after CEA. RAR/ASR push on the same link.
3. **Policy change → live RAR**: admin endpoint mutates `epc_pcc_rule` →
   invalidate ISPN → `PcrfSbb` pushes RAR to active IP-CAN sessions
   (`epc_ipcan_session` holds the Gx Session-Id).
4. Usage-Monitoring reports → PCEF CDR (`reportUsage` already wired) — close the
   loop: CCR-U usage → reconcile → CDR.
5. Deferred (not P1): ADC rules, sponsored connectivity (TS 23.203 §6), Rx VoLTE
   binding end-to-end (needs IMS/Elisa seam).

### Phase D — Infinispan hardening (both stores)

1. HSS (`CachingHssRepository`): add **IMPU→IMSI index cache** (Cx lookups by
   IMPU today hit Postgres every time), negative cache with short TTL for unknown
   IMSI (avoid hammering PG on scans), keep invalidation-on-mutation and boot
   `warm()`. **SQN and secrets never cache** (locked).
2. PCRF: cache the **policy profile** per `(imsi, apn)` decision input, invalidate
   on admin write (same pattern as HSS).
3. Both caches on `epc-cluster` DIST_SYNC — never `elisa-cluster`. HSS ×2 HA
   invalidation is P2 (single HSS node in P1).

### Phase E — Wire prove (the actual P1 gate)

1. `docker compose --profile epc up` → open5gs MME (`epc/loadtest/open5gs/README.md`).
2. S6a PCAP: AIR/AIA + ULR/ULA with full subscription AVPs (tshark filter
   `diameter.cmd.code == 318 || == 316`), then S11 Create Session to `epc-sgwc`.
3. `epc-acceptance.sh` → `results/<date>-epc.json`; `pilot-acceptance.sh` must
   pass (fails closed today).
4. CDR reconciliation prove (URR baseline → CDR) per `lessons.md`.

### Execution order

1. **A** (ULA completeness) — smallest, unblocks open5gs attach accept.
2. **E.1–E.2** wire smoke — prove S6a early; do not build C blind.
3. **C1–C2** PCRF Gx wire seam.
4. **B**, **D**, **C3–C5**, then full **E**.

### Explicit non-claims (unchanged)

No VoLTE QoS, no online charging (Gy), no HA, no 10 Gbit/s, no attach GO — each
needs its own gate. NextEPC AccUPF 30 Gbit/s is 5G DL-only, irrelevant here.
