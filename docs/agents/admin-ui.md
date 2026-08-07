# Admin UI (OTA sim-push standard)

- Disk templates: `app/html/admin/` + `app/html/partials/` with `{{TOKEN}}` substitution (no Qute).
- Vendored HTMX 2.0.4 + Alpine + `sf-shell.js` (Alpine.store `sf`, header `X-SF-Admin-Key`).
- Auth: HMAC cookie `sf_session` **or** `sf.admin.api-key` (`X-SF-Admin-Key` / `?key=`).
- Dev login: `admin` / `admin` when `sf.admin.dev-login=true`.
- HTMX fragments: `Cache-Control: no-store`, `Vary: HX-Request`.
- Dashboard shows **rates only** — FreeSWITCH status lives on `/admin/sofia` via `/admin/links/fs`.
- Design brief (grill closed): [`admin-ui-design.md`](admin-ui-design.md) — ELISA rail, amber/blue, FS Control, `/admin/ice`.
- Poll interval: **10s**. Shell: toast + postJson + copyText + theme (`sf-shell.js`).
- FS conf jail: `sf.fs.conf-dir` (default `freeswitch/conf`); hard restart: `sf.fs.hard-restart-cmd`.
- TENANT portal: `/admin/portal`. ESL / ACL / Tenants / Users / Sofia Apply: ADMIN/OPS only.
- **SIP ingress ACL** (`/admin/acl`): only approved IPs; unapproved → 403. See [`fs-control.md`](fs-control.md).
- **Media ICE/TURN** (mandatory): SDP must include ICE; TURN `typ relay` required by default → else 488. See [`media-ice-turn.md`](media-ice-turn.md). Coturn in `docker compose`.

HTTP currently on Quarkus `:8088` (pragmatic); same UX contracts as OTA `ra-http` admin.
