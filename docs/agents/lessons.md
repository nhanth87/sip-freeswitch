# Lessons

**Shared (USSDGW / OTA / Elisa / jain-slee):** workspace [`docs/agents/lessons.md`](../../../../../docs/agents/lessons.md) · skill `digicom-et-host`.

- Do not put FreeSWITCH status on the dashboard (OTA rule) — use `/admin/sofia` + `/admin/links/fs`.
- ESL soft-fails when FS is down; admin must show `eslLive=false`, never fake REGED.
- Hot-path SIP: DEBUG only for message bodies (CPS).
- UI lives on disk beside the jar; set `sf.html-dir` / `APP_HOME`.
- Dev login `admin/admin` must be disabled for prod (`sf.admin.dev-login=false`) and change HMAC secret + API key.
- Module name is **elisa** (not sip-servlet).
- **Backport to micro-jainslee `ra-sip-servlet`:** IMS P-header extract/forward, BYE dialog defer until 200, TURN/`preferRelayCandidate` (rtp_redirect), SIP-only not SBC — see `jain-slee/.../ra-sip-servlet/LESSONS_ELISA.md`.
- **Grill 2026-08-06:** URI-only From/To; dialog `remotePeer` for trunk; ProxySbb BYE→FS then 200 UA; no fake TURN relay candidate.

## Shared lab lessons (2026-08)

Cross-cutting Digicom grill (from USSDGW / AdaptiveTimeout / SS7) — short form; USSD-product minutiae stay in ussdgw. Sibling Digicom ussdgw host is **prod-bound** (PostgreSQL `ussdgw`, live Balance Plus) — not a disposable toy lab.

- **Prove on the wire**, not only logs: HTTPS → TLS/SNI filters; HTTP 200 + empty body can still fail product semantics.
- **Listen/bind what the peer addresses** — wrong SIP port / transport / ALPN (same pattern as SS7 SSN mismatch) → silent drop / no handler.
- **Ship/dist honesty:** `./tools/package-dist.sh` before deploy → ship **`dist/elisa/`** only; **never** overwrite Digicom/prod `configs/` when rsyncing jars; `db-kind` is build-time; dedicated DBs per product; **Java 25** for host Java trees.
- **Never sandbox** `./tools/package-dist.sh` / `mvn package|test` / `./tools/build-all.sh` — Shell **`required_permissions: ["all"]`** on the **first** call.
- **Prove the artifact, not the source (SIẾT):** Green `mvn test` ≠ host runs the Elisa fix. **Forbidden** to say “fixed” / “redeployed OK” until package → ship `dist/elisa/` runtime bits → restart → `:8088` ready → jar/html mtime or `jar tf` + live broken surface. Tests-only = agent failure. → root [AGENTS.md](../../AGENTS.md) · [packaging.md](packaging.md)
- **Async JDBC / flusher** must write every column the entity sets — schema alone ≠ persisted fields.
- **Identity / network scoping:** tenant or realm IDs must match the routing plane’s partition key (Elisa tenant ↔ trunk/ACL; analogous to USSD `network_id`).

## micro-jainslee Profile vs CMP — tightened rules (2026-08-08)

The “ProfileFacility is failure SoT” trap (did this wrong once in USSDGW `ussdUser`). micro-jainslee default Facility is **JVM-local** (`InMemoryProfileFacility`); it must **never** be the cluster failover source of truth.

- **One `CallSession` Profile per call — locked (C9/JP2).** Profile CMP PK = FreeSWITCH `fsUuid` (Unique-ID). Exactly **one** live `CallSession` row per call; `sipCallId` / `msisdn` are **secondary indexes only** (group multiple) — never second PK rows. Code: `et.elisa.profile.*` (`upsertCallSession` uses `getOrCreate(pf, CallSession, fsUuid, …)`; `endCallSession` removes by `fsUuid`). Index multi-match → pick the profile whose `state` is live.
- **Two Profile tables only (JP2).** `ImsSubscriber` (PK msisdn else impi) + `CallSession` (PK fsUuid); compose via `msisdn`. No mega-row; index on `directoryUserId` for FS/xml_curl at 10k lookups.
- **HSS Ki/SQN/OPc stay Postgres SoT (JP3).** Profile mirrors **non-crypto** identity only — no secrets in Profile CMP (JP4). PG `ims_subscriber` is authoritative for auth; Profile is a hot mirror / admin convenience.
- **Failover SoT = CMP + ISPN→PG, not Profile.** Live dialog restore reads `DialogCmpPersistence` (ISPN `DIST_SYNC` → PG `sf_dialog_checkpoint`), then `CallSessionProfile` as hot mirror. Treat the JVM-local ProfileFacility as **advisory** until an Infinispan `ProfileStore` is installed (`MicroSleeContainer.installDurableStore`).
- **Warm identity once, not per INVITE.** 10k subscribers = one boot pass (`ElisaProfileService.warmSubscribers`), never a per-call upsert storm. Hot path: no INFO dumps of profile bodies.

## IMS forwarding: Request-URI stays the callee; next hop is `nextHopUri` (2026-08-18)

Cross-CSCF INVITE forwarding: `SendInvite(callId, toUri, fromUri, sdp, ext, nextHopUri)` — `toUri` is the **callee** (Request-URI), the **route target** is the 6th field `nextHopUri`. Assert the *route*, never `toUri`, when testing I-CSCF LIR/Mw forwarding.

- P-CSCF MO INVITE → I/S: `SendInvite(…, e.toUri()=callee, …, icscfUri)` — `toUri` untouched.
- I-CSCF → S-CSCF: `SendInvite(…, e.toUri()=callee, …, scscf)` — put the LIR/`SERVER_NAME` in `nextHopUri`, keep `toUri`=callee. `IcscfRoutingSbbTest.invite_lirOk_forwardsToScscfWhenIOnly` was asserting `inv.toUri().contains("scscf")` (wrong — Request-URI) and failed; fixed to assert `nextHopUri()`. This is the code-side half of P1 "flowNextHopUri ≠ Contact"; host-phone (Linphone) proof still open.
- P-CSCF MT INVITE toward UE: Request-URI is a Contact/IP, so `mtFlowNextHop` resolves the binding `flowNextHopUri` — distinct from the MO domain-IMPU case above.

## EPC production P1 — tightened rules (2026-08-18)

EPC (`epc-hss` / `epc-sgwc` / `epc-pgwc` / `epc-pcef`) is a **sibling process, not Elisa** — same git repo, different JVM. Plan + gaps: [`epc-p1.md`](epc-p1.md). Hard rules:

- **`epc-cluster` never joins `elisa-cluster`.** One image → N services by role flag (`EPC_ROLE_HSS/SGWC/PGWC`). No GTP-U/S11/S6a/PFCP/PCEF inside the Elisa JVM; `epc-host` bans `ra-sip-servlet`.
- **Link truth is peer, never listen.** Diameter = `isPeerReady()` (CER/CEA + watchdog); GTP-C = Echo **Response**; PFCP = Association/Heartbeat **Response**. `LinkStatusBoard`/`S6aPeerWatch` must never report LISTEN / RA-started as UP.
- **`ra-diameter` peer seam landed (2026-08-23); wire prove still open.** `DiameterRaConfig` now has `setPeerHost/setPeerPort` (default `127.0.0.1:3868`), the Corsac transport dials the peer and exchanges CER/CEA, and `isPeerReady()` is the only UP truth. Phase-0 config blocker is closed; S6a-to-real-MME and Elisa Cx→epc-hss remain **unproven on the wire** — no live link claim until a real peer answers.
- **`ra-diameter` Corsac per-app registration fix (2026-08-23, this session).** `CorsacDiameterTransport` previously called `registerApplication` once with a flat auth list (`S6A, CX_DX, GX, CREDIT_CONTROL`) + the `commands.commons` provider package. The Corsac `DiameterParser` had already registered the common commands (CER/CEA, app 0) in its constructor, so re-registering threw `DiameterException: The request 271 is already registered for application 0` and `start()` aborted → the whole EPC Diameter plane died on boot. Fix = register **each application separately** via `registerApp(appId, apiPkg, implPkg)` (S6a / CxDx / Gx / CreditControl), deriving `Package` from already-loaded classes (`apiMarker.getPackage()`) so `Package.getPackage(String)` never returns null and the impl is registered before the compiler-fallback reloads the API interfaces. Regression test `DiameterCorsacStackBootTest` boots two real loopback stacks end-to-end; 16/16 module tests green. Full 3GPP HSS+PCRF plan: [`epc-hss-pcrf-full3gpp.md`](epc-hss-pcrf-full3gpp.md).
- **URR charging loop closed (2026-08-23).** UP baseline owns: `ra-pfcp` Usage Report IEs/codec (`PfcpUsageReport`, `encodeUsageReports`/`decodeUsageReports`, `hasQueryUrr`, TS 29.244 §8.2 thresholds), `epc-up-linux` `UrrPoller` (PERIO/VOLTH/TIMTH) + `NftUrrSampler` (kernel nft counters, never JVM packets) + `PfcpReportSink` (SESSION_REPORT_REQUEST), `epc-host` `JdbcUrrDatastore` + `V5__epc_urr_baseline.sql` (restart-safe baseline, no double-debit), PGW-C Session Report → `PcefService.reportUsage` (INTERIM CDR + `isOverGrantedQuota` guard). Knobs `epc.up.urr.mode/poll-ms/baseline.persist-ms` are consumed, not dashboard-only.
- **G3 wire prove stays open.** Unit tests ≠ MME on the wire ≠ attach PCAP ≠ gigabit. No attach GO, no HA, no VoLTE, no 10 Gbit/s claim until the gate (`epc/loadtest/pcaps/epc-attach-<ts>.pcap`, `results/<date>.json`, kill-recovery) closes. `pilot-acceptance.sh` fails closed.
- **PCEF is a library, not a service (P1).** `EpcRoles.wirePcef() == pgwc`; keep co-located. Gx/Gy/Rf only when peer `ready`; fail-closed prepaid, fail-open postpaid offline; CDR heap → PG + reconcile by charging-id (no double-debit).
- **UP is non-JVM.** `sgwu`/`pgwu` are alpine placeholders today — real Linux GTP (or VPP/DPDK) required for S1-U/S5-U/SGi. PFCP programs PDR/FAR/QER/URR; Java never forwards packets.
- **HSS data:** Ki/OPc stay out of SQL (`key_ref` → `SecretStore`/HSM); SQN via `UPDATE … RETURNING` (atomic). Dedicated `postgres-epc`/`epc`, never `elisa`/`ussdgw`, never H2.

## N–N mid-call failover — proven gap (2026-08-17)

Verified live on the wire (REGISTER 401→200 / INVITE 100→200→ACK→BYE all green), then reproduced the P0 `nn-midcall-kill` FAIL and root-caused it. This is **not** a regression you can fix by editing nginx — it is an open code-level HA gap.

- **nginx Gm VIP pins a UE to its home S-CSCF via `hash $remote_addr consistent` (UDP L4).** When the home S-CSCF dies mid-call, the UE's BYE is still hashed to the *dead* node; open-source nginx `stream` has **no active `health_check` for UDP**, so it never re-routes to the surviving peer → retransmitted BYE, no 200.
- **DO NOT "fix" it by round-robin:** the surviving peer has no local REGISTER/auth context for the UE's bare INVITE and answers **401 Unauthorized**, so round-robin breaks *normal* calls. Sticky L4 affinity is **required** for normal operation (peer-side dialog recovery is the only real fix: ISPN/CMP `DialogCheckpoint`/`CallSession` + RA `restorePortable` / SipDialogHa — still "not a live N–N prove").
- **Test calorie bug:** `uac_invite_hold_bye.xml` INVITEs `sip:alice@ims.lab` — if alice is *registered*, the terminating S-CSCF forks the INVITE to her Contact (the SIPp UAC) → loop, no 200 (the 2026-08-11 "PASS" only worked because alice had no live binding → FS Park answered). A correct mid-call test must call a real answering callee (`sip:1000`, FS MRF). Use `loadtest/sipp/uac_midcall_reg_invite_1000.xml` + `loadtest/scripts/nn-midcall-kill-1000.sh` (resolves home from PG `ims_binding.home_scscf_node`).
- **Destructive-test hygiene:** `nn-midcall-kill*` runs `docker stop` on the home S-CSCF and leaves it **down**. After the test you MUST `docker compose -f …services.yml --profile services --profile nn start scscf-2` and wait for **healthy** (~80 s) + ISPN rebalance with **both** `[elisa-scscf-1, elisa-scscf-2]` before re-running — otherwise the next REGISTER routes to the dead node and just hangs (the 401/200 loop stalls).
- **`docker exec nginx -s reload` reads the mounted `/etc/nginx/nginx.conf`.** If the mounted config changed since start, the running process still uses the old loaded config — always `nginx -t` + reload after editing, and confirm destination IPs with a capture inside the nginx netns (sidecar `--net container:nginx-services`), not the repo file alone.



Docs-only lock from PFCP/Gx analysis — **never** implement PFCP/N4, Gx, Gy, or Gx-over-Gy in Elisa. Rf ACR stub + CGRates (CDRsV1 / SessionS) are **grilled separately** (G1–G5) — see [`elisa-improve-plan.md`](elisa-improve-plan.md); do not invent PFCP/Gx paths as a shortcut.

- **SIP URI in PFCP Redirect = Diameter Gy/Ro heritage** — credit redirect on the user plane is an online-charging leftover, not IMS Core. Elisa **never** implements PFCP/N4 (no SMF/UPF ownership).
- **Gy credit redirect ≠ Gx policy** — map credit-fail UX (when OCS **UP**) to **SIP 302 / AS** later, not UPF redirect. Do not conflate with Gx PCC.
- **"Gx over Gy" (App-Id 16777225 / TS 29.210 Rel-6) = NEVER** — legacy mashed App-Id; do not resurrect. See [Gx-over-Gy trench](https://nickvsnetworking.com/tales-from-the-trenches-gx-over-gy/).
- **No Rx ⇒ do not claim dedicated-bearer VoLTE QoS** — without PCRF Rx/N5, no honest dedicated-bearer / QCI claims.
- **ICID (P-Charging-Vector) ≠ online charging done** — stamping/preserving ICID + CDR `icid` is correlation only until Rf ACR / CDRsV1 / SessionS land per improve plan.
- Nick: [PFCP and SIP Redirect](https://nickvsnetworking.com/pfcp-and-sip-redirect/) · [Gx over Gy](https://nickvsnetworking.com/tales-from-the-trenches-gx-over-gy/). Grill table: [`ims-core.md`](ims-core.md) § Charging · plan: [`elisa-improve-plan.md`](elisa-improve-plan.md).

## Elisa improve locks (2026-08-10)

Plan + Phase A/B stub: [`elisa-improve-plan.md`](elisa-improve-plan.md).

- **G3 deferred debit:** `sf.ocs.mode=deferred` — OCS down → fake-continue + durable pending (`icid` / `sipCallId`); recover → reconcile. **Never double-debit.** Not hard 403. (INVITE SessionS gate still follow-up; `PendingChargeStore` scaffold exists.)
- **G2 offline:** dual-write behind `sf.charging.dual-write` → CDRsV1 (`sf.cgrates.*`) + Rf ACR stub (`sf.rf.enabled`).
- **G4 account key:** default **`msisdn`** (HssLite / `ims_subscriber`); null → soft `impi`. Optional compose profile `cgrates`.
- **G5 pool:** `sbb-pool-max=40960` set; no 10k CPS claim until SIPp green.
