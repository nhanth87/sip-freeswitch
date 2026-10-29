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

## 2026-10-19 — Web UI wire: cache + compress + measure from the far end (cross-project, from GMLC)

- Over our ~335 ms RTT / ~15 KB/s links a page that **renders in 6–55 ms** took **~3 s per page change** in GMLC: every asset (142 KB CSS/JS) was re-downloaded with **no `Cache-Control`, no `ETag`, no gzip**. "Next.js feels faster" was the wire, not the framework.
- Any page this app serves: **content-hashed asset URLs + `Cache-Control: public, max-age=31536000, immutable`**; unversioned → `no-cache` + `ETag` → **304**; **gzip ≥ 1 KB** in the app (`Vary: Accept-Encoding`) — nginx `gzip on` does not compress proxied responses without `gzip_proxied any`; never pass a compressed body through `new String(...)`; **HTTP/2** on the TLS front.
- **Measure from the user side**, before and after: `curl -w "%{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total} %{size_download}"` + `curl -I` (cache-control / etag / content-encoding). Server time is not page time.
- Law: root `AGENTS.md` § 5 · detail: root `docs/agents/lessons.md` · reference code: GMLC `AdminWire` + `AdminPageRenderer.versionStaticUrls`.

## Elisa ↔ FreeSWITCH "doesn't run / ~100 CPS" (2026-10-28)

Measured on the mvn lab (`freeswitch/docker-compose.yml` + `./run.sh`): before fixes 50 CPS ≈ 11 % success; after: 50/100 CPS 100 % (RT 13–15 ms), 200 CPS ≈ 92 % on a loaded 4-core laptop.

- **Vanilla FreeSWITCH is a 30 CPS box.** `switch.conf.xml` ships `sessions-per-second=30`, `max-sessions=1000`, loglevel debug — and `modules.conf.xml` has **`mod_xml_curl` commented out**, so the MRF `sip:1000` dialplan from `/fs/xml` is never fetched (calls fall into vanilla `user/1000` → fail). Vanilla `internal` profile also binds `:5060` and collides with our `external.xml`. Overlays now in `freeswitch/conf/` (mvn lab); **root `docker-compose.yml` / services FS still mount none of them — fix before claiming docker-lab CPS.** Prove with `fs_cli -x "fsctl sps"` / `module_exists mod_xml_curl` / `sofia status`, never by reading XML.
- **`java -jar quarkus-run.jar` = profile `prod`** → fail-closed on missing `SF_ADMIN_*` secrets. Lab launcher passes `-Dquarkus.profile=lab`; `-D` flags **after** `-jar` are app args, not system properties (use `JAVA_OPTS`).
- **`@ConfigProperty(defaultValue = "") String`** + `x=${ENV:}` = empty = *missing* → boot fails. Blank-able props are `Optional<String>`.
- **Compile errors "cannot find symbol PortableDialogMeta / RaHaSupport / TelemetryDispatchObserver"** = a stale micro-jainslee jar clobbered in `~/.m2` (1.2.0-SNAPSHOT overwritten 2026-10-15), not broken source. Elisa pins **1.2.2-SNAPSHOT**.
- **SYNC SLEE delivery + PG on the SBB thread ≈ 100 CPS ceiling.** One disruptor worker waits per event for the SBB; per-call CMP/CallSession/CDR/HSS SQL serialised every call. Now: `microjainslee.container.event-delivery=async-commit`; PG copies are write-behind (`CoalescingWriteBehind`, coalesced per Call-ID / FS Unique-ID); fresh INVITE reads ISPN only (`loadHot`); ended Call-IDs / channels are tombstoned so BYE retransmits and late hangups never hit PG; HSS identity / MMTel / trunk / dialplan reads have 2 s TTL caches; Agroal `max-size=64`.
- **INVITE retransmits must be absorbed by the S-CSCF** (state INVITE/EARLY). ra-sip-servlet has no transaction layer: forwarding a retransmit opens a second FS dialog on the same Call-ID → ACK built from the wrong `lastResponse` → FS retransmits 200 for 32 s → channel leak. Re-INVITE only after CONFIRMED.
- **Never relay FS's responses to the BYE Elisa sent** (UE already got the local 200) — they reached UEs as `100 Trying`/`200 OK` on a finished transaction, and `SendResponse` built from `lastRequest=ACK` produced **`200 ACK`**.
- **Do not release SBB entities on `ActivityEndedEvent` yet.** It removes the leak (2–4 entities/call never removed) but `SbbRecoveryManager` treats removal as HA failure (sleep 50 ms, ISPN/PG check, `respawnLocal`) and success dropped. Open: teach recovery that activity-end release is terminal, then release.
- **Open:** `CallSessionProfile upsert failed: No profile table` on every ESL event (profile store rebound after provisioning — exception + WARN per event).
- **Bench hygiene:** warm-up run (cold JIT failed 50 CPS that later passed), `fs_cli -x "hupall NORMAL_CLEARING"` between runs, record load avg, ≥2 runs per A/B. Find the limiter with a `jcmd Thread.dump_to_file` / JFR **first** (thread dump showed the latch deadlock; JFR showed 40 % in `acquireEntity` COW list), and per-hop latency from a loopback pcap (netshoot `tcpdump` container, `--net host`).
- Lab log4j2 has no config file → RA **WARNs are invisible**; run with `-Dlog4j2.configurationFile=…` when debugging.
- **SIPp AKA ≠ 3GPP hex keys (2026-10-29).** `[authentication … aka_K= aka_OP= aka_AMF=]` `memcpy`s the *string bytes* (no hex decode) and does not expand `[fieldN]` inside the keyword — the REGISTER went out with a mangled header and Elisa (correctly) ignored it, looking like "S-CSCF never answers". Wire AKA tests use an ASCII-key subscriber (`akasipp`, recipe in `test-steps.md` W1); a subscriber with a SIP password gets **Digest-MD5**, not AKA, and admin upsert never clears `fs_a1_hash`.
- **A B2BUA on UDP needs client transactions (RFC 3261 §17.1).** ra-sip-servlet sent each INVITE/BYE once: when FS dropped an INVITE under load the call hung (no Timer A) and never failed (no Timer B); no RA `100 Trying` → SIPp retransmitted every INVITE. Fixed in the RA (`ClientTransactions`: A/B, E/F; timeout → local 408 to the SBB; `100 Trying` on new INVITE). Diagnose with per-leg counts from a pcap (`INVITE 5060→5062` vs `200 5062→5060` vs `ACK 5060→5062`), not SIPp totals.
- **FS core DB on disk caps setup rate** — `core-db-name=/dev/shm/core.db` (lab overlay). `sed -i` on a single-file bind mount makes a new inode: `docker restart` the container, `compose restart` is not enough to be sure.
- **Killing SIPp mid-run leaves answered FS legs forever** (UA gone after ACK, no RTP, no session timer). They are an abort artefact — `hupall` before the next run; real fix = RFC 4028 session timers (open).
- **CPS work starts with ms-CPU-per-call, not CPS (2026-10-29).** Measure `utime+stime` delta from `/proc/<pid>/stat` over a fixed call count after warm-up (`pgrep -f "java.*quarkus-run.jar"` — the bare pattern matches the `run.sh` wrapper) and JFR `ExecutionSample` attribution. Baseline 120 CPS: Elisa ≈14 ms/call, single ESL reader thread 66 % of a core (hard cap ≈180 CPS/node). After micro-jainslee fixes (ESL byte parser with SWAR scan, recovery sweep throttled, CMP clone exception, MDC-on-at-INFO bug, O(1) ACI reverse index + per-entity attachment set): **6.0 ms/call**, 200 CPS 9 000/9 000 on the laptop; FS 7.7 ms/call is now the larger consumer. Remaining Elisa profile is flat: core dispatch 23 %, Hibernate/PG 12 %, Netty 11 %, NIST parse+encode 11 %, S-CSCF 8 %, CallSession (4 ESL events/call) 8 %.
- **SIMD/"registers" only pay on byte scanning.** The big wins were removing work (O(N) scans per removal, a syscall per ESL message, an exception per CMP write). SWAR (8 bytes per 64-bit load) is used where bytes are scanned (ESL framing); next candidate is SIP parsing, not business logic.
