# Elisa improve plan (grill G1–G5)

**Locked:** 2026-08-10 (user). **Status:** Phase A–C production-capable (feature-flagged) + **Production P0** checklist — [`production-p0.md`](production-p0.md).  
**Pointer from:** [`ims-core.md`](ims-core.md) § Charging.

Lab/prod IMS Core charging / OCS path: offline CDR dual-write (Rf ACR + CGRates **CDRsV1**) then SessionS online with **deferred debit** when OCS is down. Not an SBC; no RTP; no PFCP/Gx/Gy. **Charging master off by default** (`sf.ims.feature.charging=false`).

---

## 1. Grill locks

| Id | Answer | Lock |
|----|--------|------|
| **G1 slice** | **B** | Jump to Rf / CGRates client next. Hygiene (Throwable + `SleeEventTrace`) is **not** blocking the first PR; still apply when touching SBBs. |
| **G2 P1 offline** | **C** | Both: Diameter **Rf ACR** in path **and** HTTP CGRates **CDRsV1** dual-write, behind feature flag(s). |
| **G3 OCS down** | **Deferred debit / fake-continue** | When OCS (CGRates SessionS) **down** → allow call with synthetic/soft credit decision (“fake” continue). When OCS **recovers** → reconcile and debit. **Not** hard 403. Config: `sf.ocs.mode=deferred` (or similar). Footgun: **no double-debit** — durable pending charge keyed by `icid` / `sipCallId`. When OCS **UP** and credit fails → SIP/AS **302** later (not UPF redirect). |
| **G4 CGRates** | **C** | Optional compose profile **`cgrates`** (sidecar when profile on; else external URL via props). **Account key default: `msisdn`** (IMS profile SoT); resolve via HssLite / `ims_subscriber`; if `msisdn` null → soft-fail to `impi` for lab. |
| **G5 pool** | **40960** | Set / keep `microjainslee.container.sbb-pool-max=40960` now. Capacity verdict only after SIPp / other SIP load tool — **do not claim 10k** until green ([`capacity-acceptance.md`](capacity-acceptance.md)). |

### Already locked never

| Never | Reason |
|-------|--------|
| **PFCP / N4** | No SMF/UPF ownership; SIP URI in PFCP Redirect is Gy/Ro heritage, not IMS Core |
| **Gx** | No PCRF/PCEF ownership |
| **Gy** | Credit on Diameter Gy user-plane path — out; map credit UX to SIP/AS |
| **Gx-over-Gy** (App-Id 16777225) | Legacy mashed App-Id — do not resurrect |
| **RTP through Elisa** | Media on FreeSWITCH (+ TURN/ICE); Elisa = SIP + control |
| **SBC** | No topology hiding / media anchoring product claims |

---

## 2. Sequence — INVITE → OCS down → CallSession → OCS up debit + CDR dual-write

```mermaid
sequenceDiagram
  autonumber
  participant UE as UE
  participant S as ScscfInviteSbb
  participant OCS as CgratesSessionS
  participant Q as PendingChargeQueue
  participant CS as CallSessionSbb
  participant Rf as RfAcrEmitter
  participant CDR as CgratesCDRsV1
  participant PG as Postgres_pending

  UE->>S: INVITE (ICID in P-Charging-Vector)
  S->>OCS: SessionS Initiate (account=msisdn)
  alt OCS DOWN (sf.ocs.mode=deferred)
    OCS--xS: timeout / connect fail
    S->>Q: enqueue pending (icid, sipCallId, msisdn, soft-allow)
    Q->>PG: durable pending row (idempotent key)
    Note over S: fake-continue — allow call (not 403)
    S-->>UE: forward INVITE / proceed session
  else OCS UP + credit OK
    OCS-->>S: Continue
    S-->>UE: forward INVITE
  else OCS UP + credit FAIL
    OCS-->>S: Reject
    Note over S: SIP/AS 302 (not UPF)
  end

  CS->>CS: CHANNEL_* / dialog correlate (fs_uuid ↔ Call-ID)
  UE->>S: BYE / hangup
  S->>OCS: SessionS Terminate (if live)
  opt OCS still DOWN
    S->>Q: finalize pending debit payload
  end

  par Offline dual-write (feature flag)
    S->>Rf: ACR STOP keyed by icid
    S->>CDR: HTTP CDRsV1 (account=msisdn)
  end

  Note over Q,OCS: sweeper when OCS recovers
  Q->>OCS: debit / reconcile pending (claim by icid)
  OCS-->>Q: OK
  Q->>PG: mark settled (never double-debit)
```

**Idempotency:** pending charge PK = `icid` preferred, else `sipCallId`; sweeper claim → debit → settle (at-most-once).

---

## 3. Phases (implementation order)

### Phase A — CGRates HTTP client + CDRsV1 — **DONE**

- [x] CDI `@ApplicationScoped` CGRates HTTP client (Java 25, Log4j2 — **no Camel**).
- [x] **CDRsV1** post path + feature flag (`sf.cgrates.cdrs.enabled` + `sf.charging.dual-write`).
- [x] Optional compose profile **`cgrates`** (sidecar when on; else `sf.cgrates.url` / props to external).
- [x] Account resolution: **msisdn** from HssLite / `ims_subscriber`; null → soft `impi`.
- [x] Bump `microjainslee.container.sbb-pool-max=40960` in `application.properties` (+ dist template).
- [x] Throwable + `SleeEventTrace` on touched SBBs (`CallSessionSbb`, `ScscfInviteSbb`).

### Phase B — Rf ACR + dual-write — **DONE (prod-capable, flagged)**

- [x] Diameter **Rf ACR** (`RfCodec` + `DiameterRfAcrEmitter`) keyed by **`icid`**; wire via `ra-diameter` when `peerReady`.
- [x] Soft-fallback structured log when peer not ready (honesty — flat string AVP map, not full nested IMS-Information).
- [x] Dual-write with CDRsV1 behind `sf.ims.feature.charging` + `sf.charging.dual-write` (+ `sf.rf.enabled` / `sf.cgrates.*`).
- [x] START on INVITE confirmed / STOP on CDR path.

### Phase C — SessionS + deferred-debit — **DONE (prod-capable, flagged)**

- [x] CGRates SessionS **Initiate / Update / Terminate** (`SessionSClient`).
- [x] Durable **Postgres** `sf_pending_charge` (Flyway V5) + claim/settle sweeper.
- [x] INVITE-path gate: `sf.ocs.mode=deferred` fake-continue + enqueue; OCS UP + credit fail → SIP **302**.
- [x] Reconcile on OCS recovery; **forbid double-debit** (claim → markSettled).

### Production P0 (ops) — **DONE (checklist)**

- [x] `application-prod.properties` + `deploy/elisa/roles/prod.env` (secrets fail-closed / no lab defaults).
- [x] `sf.admin.dev-login=false` in prod; refuse default bootstrap password.
- [x] Charging / Rf / SessionS **OFF** by default until operator enables.
- [x] Admin IMS: roles + features + cxPeerReady + eslLive + ISPN.
- [x] Docs: [`production-p0.md`](production-p0.md).
- [ ] Digicom host prove-artifact (package → rsync → restart → live surface) — **ops**, not claim from unit tests.

### N–N Profile / CMP (ops — not G1–G5 but P0 IMS)

- [x] Shared `ElisaInfinispanManager` + `DIST_SYNC` knobs; CMP = live INVITE SoT.
- [x] ProfileFacility documented JVM-local (not failover SoT).
- [x] `sf_sip_trunk` + `/admin/trunks` + `IscAsRouter` pick/fallback.

### Hygiene (cross-cutting)

- [x] Touched SBBs: catch/log **Throwable**; emit **`SleeEventTrace`**.
- [x] Pool: `microjainslee.container.sbb-pool-max=40960` — **no 10k CPS claim** until SIPp green.

---

## 4. Engineering constraints

| Rule | Detail |
|------|--------|
| **JDK** | Java **25** only (`mise` → `zulu-25`) |
| **Logging** | Log4j2 only — no SLF4J/logback dual |
| **Types** | Prefer **immutable records** for commands / CDR / pending payloads |
| **No Camel** | HTTP client stays plain CDI / JDK HttpClient (or existing Elisa HTTP style) |
| **No SBB instance maps** | Activity / CMP / store — not `Map` on SBB instance fields for dialog state |
| **Link truth** | OCS/CGRates “live” = peer evidence (HTTP/SessionS); Diameter = `peerReady`; ESL = `eslLive` |
| **Prove artifact** | Package → ship `dist/elisa/` → restart → prove live path ([`packaging.md`](packaging.md) · [`production-p0.md`](production-p0.md)) |

---

## 5. Explicit non-goals

- PFCP/N4, Gx, Gy, Gx-over-Gy, PCRF Rx/N5, PCEF
- RTP / media through Elisa; SBC / topology hiding
- Hard **403** when OCS down under `sf.ocs.mode=deferred`
- Claiming **10 000 INVITE/s** before SIPp (or agreed SIP load tool) green
- Blocking Phase A on full SBB hygiene sweep (G1=B)
- Shipping Camel, Spring WebClient stacks, or WAR packaging for Digicom
- Double-debit / best-effort-only pending without durable key

---

## 6. Pointers

| Doc | Role |
|-----|------|
| [`production-p0.md`](production-p0.md) | Go-live checklist |
| [`ims-core.md`](ims-core.md) § Charging | Grill summary + link here |
| [`lessons.md`](lessons.md) § PFCP / Gx / Gy + improve locks | Footguns + deferred-debit |
| [`capacity-acceptance.md`](capacity-acceptance.md) | 10k gate |
| [`user-profile.md`](user-profile.md) | IMS profile / msisdn SoT |
| [`architecture.md`](architecture.md) | Not-SBC shape |

---

## Suggested property knobs

```properties
sf.ims.feature.charging=false
sf.ims.feature.mmtel=true
sf.ims.feature.enum=false

sf.cgrates.enabled=false
sf.cgrates.url=http://127.0.0.1:2080
sf.cgrates.cdrs.enabled=false
sf.cgrates.sessions.enabled=false
sf.cgrates.account-key=msisdn
sf.charging.dual-write=false
microjainslee.container.sbb-pool-max=40960

sf.rf.enabled=false
sf.rf.destination-host=ocs.ims.lab
sf.rf.destination-realm=ims.lab

sf.ocs.mode=deferred
sf.ocs.session.enabled=false
sf.ocs.pending.sweep-interval-sec=30

sf.admin.dev-login=false   # prod
```

Compose: profile `cgrates` starts sidecar (`docker-compose.services.yml`); without profile, URL points at external CGRates.
