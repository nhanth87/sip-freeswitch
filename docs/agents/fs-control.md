# FreeSWITCH control plane

Source of truth = **PostgreSQL**. Runtime = FreeSWITCH. Control = **ESL** + **mod_xml_curl**.

## Live truth

| Field | Meaning |
|-------|---------|
| `eslLive` | ESL auth + successful command |
| `sofia` | Raw `sofia status` (do not invent UP) |
| `channels` | Parsed `show channels count` |

## Apply / Start / Stop

- `POST /admin/apply` → `reloadxml`
- `POST /admin/start` → `sofia profile external start`
- `POST /admin/stop` → `sofia profile external stop`

## xml_curl

FreeSWITCH → `POST http://elisa:8088/fs/xml` with `section=directory|dialplan`.
Elisa renders XML from `sf_extension` / `sf_dialplan_rule`.

## SIP ingress ACL (Elisa)

Default: **deny** (`sf.sip.acl.enabled=true`, `sf.sip.acl.default-action=deny`).

| Action | Meaning |
|--------|---------|
| `allow` | Approved peer CIDR/IP — INVITE/REGISTER accepted |
| `deny` | Explicit block (wins over overlapping allow) |

Rules live in `sf_acl_entry` with `list_name=sip-ingress`. Admin UI: `/admin/acl`.
Unapproved INVITE → SIP **403 Forbidden**. Peer IP from top Via `received=` / host (INVITE) or Contact host (REGISTER).

After CRUD, call **Reload ACL** or rely on POST handlers that call `sipAcl.reload()`.
