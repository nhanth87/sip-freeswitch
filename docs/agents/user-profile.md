# User Profile + Call Session (grill P1=C, P2=2)

Telephony identity and live call correlate for Elisa IMS Core + FreeSWITCH MRF/AS.

## Locks

| Id | Decision |
|----|----------|
| **P1** | IMS-first: `ims_subscriber` (+ MSISDN, tenant, `fs_a1_hash`) is SoT for HSS Ki/SQN; FS directory derives |
| **P2** | Dual-write live sessions: Infinispan `CallSessionStore` + Postgres `sf_call_session` (until SLEE Profile path lands) |
| **P3** | No RTP through Elisa — FS UUID is control-plane only |
| **P4** | `sf_portal_user` = admin login only |
| **P5** | CDR enriched with `sip_call_id` + `msisdn` via session join |
| **JP\*** | **JAIN SLEE 1.1 ProfileFacility** for SBB hot identity/session — see [`jainslee-profile.md`](jainslee-profile.md) (**implemented** lab P1; not HTMX `/admin/profiles`) |

## Clarification — two different “profiles”

| Layer | What it is | Path |
|-------|------------|------|
| **SLEE Profile (JSR-240 §10)** | `ProfileFacility` / ProfileTable / ProfileSpecification CMP — SBB shared hot store | [`jainslee-profile.md`](jainslee-profile.md) |
| **Admin HTMX** | Operator UI over Postgres `ims_subscriber` | `/admin/profiles` — **not** the SLEE facility |

## Identity (Postgres today)

- Table: `ims_subscriber` (see Flyway [`V1__elisa_baseline.sql`](../../elisa/src/main/resources/db/migration/V1__elisa_baseline.sql))
- Admin: `/admin/profiles`
- Lookup: `HssLiteService.resolveIdentity` / `findByMsisdn`
- xml_curl: `FsXmlCurlResource` prefers IMS profiles; `sf_extension` fallback if empty

## Live session (today)

```text
CHANNEL_CREATE → CallSessionSbb → CallSessionService.upsertFromFs
  → ISPN fs:{uuid} + sip:{callId}
  → PG sf_call_session
CHANNEL_HANGUP → endByFsUuid (ISPN remove + PG ended_at)
CDR ingest → join by call_uuid → stamp sip_call_id / msisdn
```

ISC INVITE stamps `X-Elisa-Sip-Call-Id` (lab). FS dialplan may:

```xml
<action application="export" data="nolocal:elisa_sip_call_id=${sip_h_X-Elisa-Sip-Call-Id}"/>
```

Admin: `/admin/sessions` (HTMX poll 3s).

## Target (SLEE Profile — **implemented**)

```text
REGISTER / admin provision → PG HSS (Ki/SQN) + ProfileFacility upsert ImsSubscriber[msisdn|impi]
CHANNEL_* → ProfileFacility upsert CallSession[fsUuid] (indexes: sipCallId, msisdn)
HANGUP → remove CallSession profile (+ ISPN/PG dual-write unchanged)
```

Code: `et.elisa.profile.*`, bootstrap via `ElisaProfileService.provisionTables()`.

Detail + ProfileSpecification field list: [`jainslee-profile.md`](jainslee-profile.md).

## Non-goals

- Portal ↔ telephony merge
- Elisa media session
- Dropping `sf_extension` in this slice
- Treating HTMX `/admin/profiles` as JAIN SLEE Profile
