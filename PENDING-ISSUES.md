# PENDING ISSUES — MVNO improve plan (W1/W2/W3) + Log4j2-only landing

**Updated:** 2026-08-26 · Plan: [`docs/agents/mvno-improve-plan.md`](docs/agents/mvno-improve-plan.md)
**Landed so far:** W1-a, W1-b, W2-a (Sprint 1) · W3-a, W3-b (Sprint 2 core) · Log4j2-only sweep (elisa 19 files + both poms + dist scripts).
**Test policy:** full test runs are **deferred to the end-of-features batch test** per owner — compile + enforcer green only until then.

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
