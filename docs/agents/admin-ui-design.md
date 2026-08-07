# Elisa admin UI — design brief (GRILL CLOSED)

Source of truth UX: **OTA sim-push** (`disk html` + `{{TOKEN}}` + HTMX).  
Skills: grill-me · htmx · quarkus-patterns · java-patterns · frontend-design.

## All locks

| Id | Decision |
|----|----------|
| **G1** | Brand: **ELISA** |
| **G2 / G2b** | Amber Blue: `--color-signal: #e8a317`, `--color-line: #3b82f6` |
| **G3** | TENANT: status + ICE read + own CDR |
| **G4** | OPS ≈ ADMIN except Users / API keys |
| **G5** | Calls: parsed channel table + HTMX poll (no raw ESL dump) |
| **G6** | Hangup: ADMIN only + confirm |
| **G7** | Dashboard: ESL LIVE/DOWN + channel count badge |
| **G8** | Poll: **10s** |
| **G9** | Side-rail Alpine (Live · Management · Admin · Network · Portal) |
| **G10** | ICE URL: **`/admin/ice`** (redirect `/admin/codecs`) |
| **G11** | Shell: toast + postJson + copyText + theme |
| **G12** | Apply / sofia reload: **System + Sofia only** |
| **G13** | **FS Control** under Management |
| **G13b** | ADMIN **+ OPS** (edit + restart) |
| **G13c** | Jail: **`freeswitch/conf/**` only** |
| **G13d** | Soft reload default + separate **Hard restart** |
| **G13e** | Tree + **Monaco/CodeMirror** editor |

### Role matrix

| Capability | ADMIN | OPS | TENANT |
|------------|-------|-----|--------|
| Dashboard rates + ESL badge | ✓ | ✓ | portal status |
| Calls (10s) | ✓ | ✓ | — |
| Hangup | ✓ | — | — |
| CDR | all | all | own |
| Management + **FS Control** | ✓ | ✓ | — |
| ICE read / write | ✓/✓ | ✓/✓ | ✓/— |
| Tenants · ESL · System | ✓ | ✓ | — |
| Users / API keys | ✓ | — | — |

## IA

```
ELISA [collapse]
├── Live          Dashboard · Calls · CDR
├── Management    Sofia · Gateways · Directory · Dialplan · ACL
│                 Conferences · IVR · Callcenter · Recordings
│                 FS Control
├── Admin         Tenants · Users* · ESL · System
├── Network       /admin/ice
└── Portal        (TENANT)
```

## Implementation status (2026-08-07)

Grill **closed**. Scaffold landed:

- Side-rail shell + amber/blue tokens + `sf-shell.js` (toast/postJson/copyText/theme)
- Dashboard rates + ESL LIVE/DOWN + channel badge (10s)
- Calls parsed table + ADMIN hangup
- `/admin/ice` (+ `/admin/codecs` redirect)
- **FS Control**: detail, conf jail tree, Monaco editor, mkdir, soft reload, hard restart
- Config: `sf.fs.conf-dir`, `sf.fs.hard-restart-cmd`

Still thin stubs: Tenants/Users CRUD, tenant-scoped CDR filter, IVR deep forms — grill: [`admin-tenants-ivr-design.md`](admin-tenants-ivr-design.md).

