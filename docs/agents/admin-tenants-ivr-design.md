# Elisa — Tenants / Users / CDR / IVR / Cluster (GRILL CLOSED)

## Product goal

Elisa (micro-jainslee): **control FreeSWITCH**, **keep sessions**, **N–N cluster** (Elisa + SIP profiles + FS).

## All locks

| Id | Decision |
|----|----------|
| **T1** | Tenant OTA-full: kind, http_api_key, source_addr_alias |
| **T2** | Tenants: ADMIN + OPS |
| **T3** | Portal Users UI: TENANT + OPS (ADMIN bootstrap) |
| **T4** | Login: DB bcrypt only |
| **T10** | Trunk = `kind=TRUNK`; SIP / WS / HTTP2 / HTTP3 |
| **T11** | Directory call-user + Portal users (ADMIN) |
| **T12** | OPS = reseller; no `/admin/users` |
| **T13** | Cross-tenant only via trunk tenant |
| **T14** | OPS sees only tenants they created |
| **T15b** | Trunk on `sf_tenant`: `trunk_transport`, `trunk_config` (PG) |
| **T5** | CDR tenant: var → extension fallback; trunk leg → trunk tenant |
| **T6 / T6b** | UI subset; DB = **full RestComm-shaped CDR** + tenant/reseller/trunk |
| **T7** | `sf_ivr_menu` + `sf_ivr_entry` |
| **T8** | Form + Monaco XML |
| **T9** | IVR publish: **xml_curl primary** + optional conf export/reload |
| **C1** | Session store: **Infinispan** + PG for CDR/config |
| **C2** | SIP: **VIP/LB + profile-per-node affinity** (with FS pair) |
| **C3** | Elisa↔FS: **paired first** (Elisa-i ↔ FS-i + failover list); mesh later |

## RestComm CDR

Base: `restcomm_call_detail_records` columns → `sf_cdr` (+ `tenant_id`, `trunk_tenant_id`, `reseller_id`, `source_addr`, optional `payload`).

## Cluster sketch

```text
UA ──VIP/LB──► Elisa-1..N  (Infinispan: dialog/session)
                 │ paired ESL
                 ▼
            FreeSWITCH-1..N  (sofia profile affinity)
Config/CDR/Tenants/IVR ── PostgreSQL (shared)
IVR runtime ── xml_curl any Elisa ──► FS; optional conf export
```

## Implementation order

1. ~~Flyway V3~~ — tenant trunk/reseller, RestComm CDR, IVR, cluster pair stubs  
2. ~~TenantService / PortalUserService / DB login + bootstrap ADMIN~~  
3. ~~HTMX Tenants / Users / Directory / CDR scoped / IVR~~  
4. ~~Infinispan dialog checkpoint (C1) + paired FS registry wiring (C3) + VIP docs (C2)~~  
5. ~~IVR conf export path (T9 optional) + SIP a1-hash for directory~~  

## Implementation status (2026-08-07)

Landed in `elisa/`: Flyway baseline [`V1__elisa_baseline.sql`](../../elisa/src/main/resources/db/migration/V1__elisa_baseline.sql) (squashed), bcrypt login, tenant/trunk CRUD, portal users ADMIN-only, CDR RestComm-shaped ingest+scoped UI, IVR menus/entries + `/admin/ivr/xml/{tenant}/{name}`. Datastore notes: [`datastore.md`](datastore.md).

**Runtime wiring:** `DialogCheckpointStore` (Infinispan LOCAL/DIST), `FsPairRegistry` + `EslFailoverService`, `ProxySbb` checkpoints, SIP directory `a1-hash`, `IvrPublishService` + `POST /admin/ivr/publish/{id}`, docs [`cluster-c2-vip-affinity.md`](cluster-c2-vip-affinity.md).
