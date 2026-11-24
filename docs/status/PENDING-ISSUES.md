# PENDING ISSUES — MVNO improve plan (W1/W2/W3) + Log4j2-only landing

**Refreshed:** 2026-11-20 (added § HSS links; every older item below is unchanged from 2026-08-26) · **Updated:** 2026-08-26 · Plan: [`docs/agents/mvno-improve-plan.md`](../agents/mvno-improve-plan.md)
**Landed so far:** W1-a, W1-b, W2-a (Sprint 1) · W3-a, W3-b (Sprint 2 core) · Log4j2-only sweep (elisa 19 files + both poms + dist scripts).
**Test policy:** full test runs are **deferred to the end-of-features batch test** per owner — compile + enforcer green only until then.

## HSS links (2026-11-20)

Standalone `HssMain` with Diameter S6a/Cx, SS7 MAP SAI (SCTP only) and HTTP/2 Nudm-UEAU on one AuC.
Doc: [`docs/agents/hss-links.md`](../agents/hss-links.md). Changes are in the working tree, not committed.

**Done (unit / integration proven, not wire-proven):**

| # | Item |
|---|------|
| H1 | `epc-crypto` `FiveGAka`: KDF (TS 33.220 B.2), KAUSF (TS 33.501 A.2), XRES* (A.4, low 128 bits), HXRES* (A.5) — `FiveGAkaTest` 20 |
| H2 | `epc-hss` `UeauService`: Nudm-UEAU generate-auth-data, SUPI + null-scheme SUCI, resync, causes per cached TS 29.503 v16.5.0 — `UeauServiceTest` 14 |
| H3 | `UdmUeauSbb` on `ra-http-server`; HTTP/2 h2c prior knowledge proven on loopback (`UdmUeauHttp2Test`); `jackson-bom` 2.21.8 in epc-hss pom |
| H4 | `HssLinks` / `HssApp` / `HssBootstrap` / `HssMain`: only enabled links boot, `Bound.verify()`, exit 2 on link failure, SS7 non-SCTP refused, public KEK refused unless `hss.lab=true`; no-arg SBB constructors removed (auto-deploy trap) |
| H5 | `mise.toml` (root + `epc/`): `java = "zulu-25"` (the pinned `zulu-25.34.17` was not installed) |
| H6 | Full `epc` reactor `mvn -o test` (2026-11-20, exit 0, 0 fail/err/skip): epc-api 363 · epc-crypto 448 · epc-hss 185 · epc-sgwc 27 · epc-pgwc 28 · epc-pcef 49 · epc-pcrf 47 · epc-up-linux 50 · epc-host 104 = **1301** |

**Open:**

| # | Item | Detail |
|---|------|--------|
| H-W1 | **Wire proof — none yet** | no real MME/S-CSCF (Diameter), no VLR/SGSN/STP (SS7 never on a live SCTP association), no AUSF; no 3GPP-published 5G AKA vector (self-derived KAT only) |
| H-R1 | Blocking JDBC on the SBB event thread | `JdbcHssRepository` (`reserveSqn`) runs on the event thread |
| H-R2 | `HssSbb` / `HlrSbb` have no catch-all in `onEvent` | JDBC error or an S6a AIR with bad AUTS / missing RAND throws → no AIA / no MAP answer; peer waits for its own timer (`UdmUeauSbb` already answers 500) |
| H-R3 | Startup status printed once | `peerReady` / `m3uaRouteReady` are read `false` at boot; no live link status afterwards |
| H-R4 | Diameter default peer 127.0.0.1 | empty `hss.diameter.peers` = one server-role peer on loopback → set `hss.diameter.peers` for a real MME/S-CSCF |
| H-R5 | Home PLMN hard-coded 001/01 in `HssMain` | AIR without Visited-PLMN-Id → KASME bound to 001/01 |
| H-R6 | `hss.jdbc.password` default `epc` | require it outside a lab |
| H-R7 | **epc-host KEK default** | compose `epc-hss` (epc-host) still defaults `epc.hss.secret.kek-hex` to the public KEK: `epc/epc-host/src/main/resources/application.properties`, `dist/epc/configs/application.properties`, `EpcRuntime.java` — not covered by the new `hss.lab` rule |
| H-R8 | Jackson BOM | any module that puts Vert.x next to `ra-jss7` needs `jackson-bom` 2.21.8 |
| H-R9 | SBB factory rule | SBBs registered via `registerSbbType` must not have a no-arg constructor |
| H-R10 | 3GPP gaps vs `epc-hss-pcrf-full3gpp.md` Phase A.3 | AIR returns one vector (ignores Number-Of-Requested-Vectors); no Δ window on resync; Cx MAR has no AUTS resync |
| H-R11 | PUR has no MME allowlist check | any Diameter peer can mark a subscriber purged |
| H-P1 | `HssMain` packaging | no dist / run script / compose service starts it; prove-the-artifact not done |
| H-D1 | Owner decisions pending | Elisa `HssLiteService` (second AuC + SQN space) · ~~GitHub repo visibility~~ decided 2026-11-24: **public** |

## Sprint 3 / next code

| # | Item | Detail |
|---|------|--------|
| P1 | **W1-c — Leg A1 keepalived VRRP pair** | compose sidecars (`network_mode: service:scscf-1/2`, `NET_ADMIN`), unicast peers on sf-net static IPs; nginx upstream → VIP-1/VIP-2 (hash semantics untouched). D1 locked: A1 lab / A2 declared prod direction |
| P2 | **W3-c — PCO tier-1 wire prove** | extend gtpc smoke client: CS Request with requested-PCO → tshark asserts IE 78 `0x000C`=VIP on BOTH hops (S11 + S5-C); pcap under `epc/loadtest/pcaps/` |
| P3 | **open5gs MME PCO relay verify** | `epc/loadtest/open5gs/config/mme.yaml` has no PCO/p-cscf lines yet — confirm mmed relays NAS↔GTP PCO before W3-c interop claim |

## Deferred gates (prove-artifact — NOT done)

| # | Gate | Why blocked |
|---|------|-------------|
| G1 | **Batch test run** (owner-mandated single pass at end of features): elisa suite (199 green pre-log4j2-sweep), epc reactor suite (102 green pre-log4j2), first execution of `Log4j2OnlyPolicyTest` ×2 | features still landing |
| G2 | **Repackage artifacts**: `tools/package-dist.sh` + `tools/package-dist-epc.sh` after log4j2/W3-b changes; dist/elisa tracked html+configs in repo are stale vs current poms | ship gate §6 production-p0 |
| G3 | **W1-d live kill prove ×2**: `nn-midcall-kill-1000.sh` BYE 200 post-kill + pcap + ESL remap evidence; destructive hygiene between runs | needs W1-c Leg A first |
| G4 | **W2-d provision prove**: API-provisioned identity → S6a attach → SIPp AKAv1-MD5 REGISTER → INVITE 1000 → CDR, scripted end-to-end | needs W2-b/c |

## Remaining plan items (not started)

- **W2-b** EPC subscriber REST on epc-host (PUT/GET/suspend, TXN over `epc_subscriber`+`epc_secret`+`epc_apn_profile`, admin-key auth, tenant_id col)
- **W2-c** Elisa fan-out `/api/mvno/v1/subscribers` + HTMX plane badges
- **W3-d** UE/NAS-level PCO delivery — deferred behind epc-p1 Phase-2 attach (G3 attach PCAP)
- **Per-row APN PCO override**: V8 columns `epc_apn_profile.pcscf_csv/dns_csv` exist but emission reads env defaults only (`PcoDefaults`) — wire row override when W2-b provisioning lands

## Cross-tree dependency

- Vendor **ra-gtpv2c PCO codec (IE 78)** lives in `~/ethiopia-working-dir/jain-slee/jain-slee/vendor-ras/ra-gtpv2c` (separate repo, branch `micro-jainslee-2`, remote `nhanth87`). Committed+pushed alongside this repo. epc resolves it from `.m2` as `com.microjainslee:ra-gtpv2c:1.2.0-SNAPSHOT`.

## Known footguns for the batch test

- elisa log4j2 sweep renamed jboss `*f()` calls mechanically (throwable moved last) — watch tests asserting log strings
- `ImsSecretMigrator` drops plaintext `ims_subscriber.ki/.opc` at boot — any test/dev DB with legacy rows migrates once; fresh PG gets V10-only path
- admin POST `/admin/subscribers` now **400s without ki/opc unless dev-login=true** — SIPp harnesses that relied on lab-pair endpoint are unaffected (`dev-login=true` default in lab)
