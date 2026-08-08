# Lessons

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
- **Ship/dist honesty:** `package-dist` before deploy; **never** overwrite Digicom/prod `configs/` when rsyncing jars; `db-kind` is build-time; dedicated DBs per product; **Java 25** for host Java trees.
- **Async JDBC / flusher** must write every column the entity sets — schema alone ≠ persisted fields.
- **Identity / network scoping:** tenant or realm IDs must match the routing plane’s partition key (Elisa tenant ↔ trunk/ACL; analogous to USSD `network_id`).
