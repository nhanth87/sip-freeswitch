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

## PFCP / Gx / Gy footguns (2026-08-10)

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
