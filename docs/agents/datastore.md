# Elisa datastore — PostgreSQL baseline + Cassandra sharding check

## Current (locked)

- **Runtime SoT:** PostgreSQL via Quarkus JDBC + Flyway + Hibernate Panache.
- **Single migration:** [`elisa/src/main/resources/db/migration/V1__elisa_baseline.sql`](../../elisa/src/main/resources/db/migration/V1__elisa_baseline.sql) (squashed former V1–V5).
- Hot path also uses **Infinispan** (dialogs, reg home, call sessions) — not Cassandra.
- **CMP recovery:** `sf_dialog_checkpoint` dual-writes with `DialogCheckpoint` ISPN — [`cmp-recovery.md`](cmp-recovery.md).

### Existing lab DB that already applied old V1–V5

Flyway checksum/history will not match. Prefer:

```bash
# lab only — wipe and remigrate
dropdb elisa && createdb -O elisa elisa
# then start Elisa (migrate-at-start)
```

Or: truncate `flyway_schema_history`, drop all app tables, restart.

## Cassandra + sharding — evaluation (not enabled)

| Data | PG today | Cassandra fit | Suggested partition key |
|------|----------|---------------|-------------------------|
| `sf_tenant`, portal, IVR, ACL, dialplan | Strong FK / small | **Poor** (relational, low churn) | Keep PG |
| `ims_subscriber` (HSS Ki/SQN) | Need atomic SQN | **Poor** (LWT heavy; races) | Keep PG (+ ISPN pending) |
| `ims_binding` | Sticky home | **Medium** | `((impu), contact)` TTL |
| `sf_call_session` live | Dual-write ISPN+PG | **Medium** — ISPN stays hot; Cassandra archive optional | `((fs_uuid))` or `((tenant_id), started_at)` |
| `sf_cdr` | RestComm-shaped | **Good** (append-heavy, time range) | `((tenant_id, day_bucket), start_time, call_uuid)` |
| `sf_recording` meta | Path refs | **Good** | `((tenant_id), call_uuid)` |

### Sharding model (if adopted later)

```text
                    ┌──────────── PG ────────────┐
 Config / HSS / IVR │ tenants, ims_subscriber,   │
 transactional SQN  │ portal, acl, ivr, cluster  │
                    └────────────┬───────────────┘
                                 │
              ISPN (live)  CallSession / Dialog / RegHome
                                 │
                    ┌────────────▼────────────┐
 CDR / history      │ Cassandra (token ring)  │
 optional archive   │ shard by tenant+day or  │
                    │ msisdn hash             │
                    └─────────────────────────┘
```

**Token-aware drivers:** DataStax Java driver with `token-aware` load balancing; Quarkus has no first-class Cassandra in Elisa BOM today — would add `cassandra-quarkus` or plain DataStax mapper as a **separate module**, not replace Panache entities.

**Do not** put IMS AKA SQN or portal auth on Cassandra in Phase 1 of any hybrid.

### Decision for now

1. Stay on **PostgreSQL** for all Panache tables (this squash).
2. Keep **Infinispan** for live session/dialog.
3. Revisit Cassandra **only for CDR/history scale-out** when SIPp/CDR volume exceeds PG comfort; partition key `((tenant_id, yyyymmdd), start_time, call_uuid)`.
4. No Cassandra dependency in `elisa/pom.xml` until a grill lock enables it.

## Hybrid PG + Cassandra @ 1k TPS/server

**Verdict: YES — hybrid is viable.** Run PG and Cassandra in parallel with clear workload split. Do **not** put HSS/SQN or config on Cassandra.

### Clarify “TPS”

| Metric | Meaning | Who owns it | 1k/server realistic? |
|--------|---------|-------------|----------------------|
| **INVITE CPS** | sustained INVITE/s at SIP edge | `ra-sip-servlet` + SBB + ISPN | **Yes** (lab target is 10k; 1k is well below) |
| **REGISTER TPS** | REGISTER upserts | BindingStore (PG) + ISPN reg-home | **Yes** if PG not on every INVITE |
| **CDR write TPS** | hangup → `sf_cdr` insert | `CdrIngestService` | **Yes** on PG alone at 1k; Cassandra when retention/query scale hurts |
| **DB ops/s** | all JDBC + CQL | mixed | Ambiguous — do not use as one number |

Rule: say **INVITE/s**, **CDR writes/s**, or **REGISTER/s** — not bare “TPS”.

Cluster horizon (1M SIP transactions/s, not a claim): [`capacity-1m.md`](capacity-1m.md). INVITE hot path stays ISPN; `sf.cmp.pg-sync=false` at that profile.

### Workload split (locked direction)

```text
PostgreSQL (SoT, transactional)
  • sf_tenant / portal / ACL / IVR / dialplan
  • ims_subscriber (Ki / SQN — atomic commit only here)
  • ims_binding (durable; ISPN mirrors home)
  • optional short-retention CDR buffer / admin join queries

Infinispan (live, hot)
  • DialogCheckpoint, RegistrationHome, CallSession
  • INVITE path must not wait on Cassandra

Cassandra (later — append history only)
  • sf_cdr archive / long retention
  • sf_recording meta
  • optional ended-session archive (not live CallSession)
```

### Topology for ~1k INVITE/s per Elisa node (no over-engineering)

1. **Now (PG-only + ISPN):** keep current dual-write CallSession (ISPN hot + PG durable). CDR stays Panache → PG. Tune PG (`max_connections`, batch/async CDR if needed). **No Cassandra dependency.**
2. **When to add Cassandra:** sustained CDR volume or retention (months of history, multi-tenant time-range scans) exceeds PG comfort — not because 1k INVITE/s needs it.
3. **Partition keys (if enabled):**
   - CDR: `((tenant_id, yyyymmdd), start_time, call_uuid)`
   - Recording meta: `((tenant_id), call_uuid)`
4. **Ingest pattern:** prefer **async single-writer** (ESL/CDR → queue/worker → Cassandra) over sync dual-write on the SIP thread. Optional short PG buffer for admin “last N minutes”; do **not** dual-write HSS/SQN.
5. **Avoid sync dual-write** of CDR to PG+Cassandra on the hot path (split-brain risk, latency). If both needed temporarily: write PG first (or queue), then async fan-out / CDC — never block INVITE on CQL.

### Risks

| Risk | Mitigation |
|------|------------|
| Dual-write consistency (PG vs C*) | Async ingest; eventual consistency OK for history, not for SQN |
| HSS SQN races | **PG only** (+ in-memory pending AKA); never C* LWT for SQN |
| Panache vs Cassandra driver | Separate module / DAO; keep Panache entities for PG; DataStax/`cassandra-quarkus` for C* only |
| Live session on C* | No — ISPN stays hot; C* = archive after hangup |
| Treating 1k TPS as one DB number | Split metrics; SIP CPS ≠ CDR writes/s |

## Config knobs (PG)

```properties
quarkus.datasource.db-kind=postgresql
quarkus.flyway.migrate-at-start=true
```
