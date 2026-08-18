# Cluster horizon — 1 000 000 SIP transactions/s

**This is a scale-out ladder, not a measurement.** Do not claim 1M TPS (or even 10k INVITE/s) until the matching acceptance gate is green on named hardware.

Per-node claim gate stays **10 000 INVITE/s** — [`capacity-acceptance.md`](capacity-acceptance.md).

## Say the metric, never bare “TPS”

| Name | Meaning | Plane |
|------|---------|--------|
| **SIP TPS** | SIP requests+responses handled /s (transactions, not RTP) | `ra-sip-servlet` + CSCF SBBs |
| **INVITE CPS** | INVITE attempts /s at the **P-CSCF** edge | same JVM, counted at P (or S if combined) |
| **REGISTER/s** | REGISTER upserts | BindingStore / HSS — **not** INVITE hot path |
| **CDR writes/s** | hangup ingest | async; not INVITE |
| **RTP / GTP-U** | media / user plane | FreeSWITCH + kernel GTP-U — **never** Elisa JVM |

Rule (same as [`datastore.md`](datastore.md)): say **INVITE/s**, **SIP TPS**, **REGISTER/s**, or **CDR writes/s**.

One INVITE setup is several SIP transactions (INVITE + 100 + 18x + 200 + ACK, then BYE/200). Rough lab factor:

```text
1 INVITE CPS  ≈  5–8 SIP TPS  (setup only; +2 if BYE in the same second)
1M SIP TPS    ≈  125k–200k INVITE/s cluster-wide
1M INVITE/s   ≈  100 × 10k-INVITE/s nodes   (after the 10k/node gate is green)
```

**1M SIP TPS is not 1M INVITE/s and is not one JVM.**

## Ladder (locked honesty)

```text
lab (today)     →  measure CPS; do not invent numbers
10k / node      →  SIPp green on one P-CSCF  [capacity-acceptance.md]  ← claim gate
100k cluster    →  ~10 P-CSCF + sharded S-CSCF + nginx L4 hash
1M SIP TPS      →  ~50–80 signaling boxes  (or ~100 if the number is INVITE/s)
```

Media (Opus/G.722) and EPC GTP-U scale **beside** this ladder, not inside it.

## Shape (do not invent a second SIP stack)

```text
UE ──Gm──► nginx L4 (hash $remote_addr) ──► many P-CSCF
                 │
                 ▼
              I-CSCF  ──Cx LIR──► HSS (off INVITE CPU; cache / ISPN)
                 │
                 ▼
         S-CSCF shard(IMPU) ──ISC──► FreeSWITCH pair (1:1, not CPS)
                 │
                 └── ISPN DIST_SYNC live dialog  (PG durable is NOT on INVITE)
```

| Must | Must not |
|------|----------|
| Count CPS at P-CSCF; LongAdder only on hot path | Micrometer `Counter.increment` per INVITE |
| INVITE CMP = Infinispan; `sf.cmp.pg-sync=false` at 1M profile | Dual-write PG/Cassandra on INVITE |
| CallSession PG dual-write stays on **ESL CHANNEL_*** (not INVITE CPS) | Put user plane in Elisa |
| Charging / ENUM / ICE-STUN off on **CPS bench** | INFO dumps of SIP bodies |
| nginx `worker_connections` ≥ 16k; SIP `access_log off` | xml_curl / admin HTTP on the SIP thread |
| Shard S-CSCF by IMPU (`CapacityShards`) | One JVM `sbb-pool-max=1_000_000` as a “fix” |
| GTP-U / RTP off-JVM | Put user plane in Elisa |

## Activate foundations (not a claim)

Quarkus profile (compose / `run.sh` extra profile):

```bash
QUARKUS_PROFILE=capacity-1m,prod
# or: SF_CAPACITY_PROFILE=cluster-1m SF_CMP_PG_SYNC=false
```

See `elisa/src/main/resources/application-capacity-1m.properties`.

- **Lab HD A–B calls** stay on default profile (ICE/TURN on, PG CMP dual-write on).
- **CPS / TPS bench** uses `capacity-1m` (ICE off, charging off, CMP PG off INVITE).

SIPp: `./tools/bench-cps.sh` ramps to **10k INVITE/s on one edge**. A 1M run needs many generators + many P-CSCF; do not add `1000000` to the default ramp.

## Hardware honesty

Java SIP CP on a 64C box is **not** 1M TPS. EPC note: [`epc/docs/agents/capacity-64c-10g.md`](../../epc/docs/agents/capacity-64c-10g.md) — 10 Gbit/s is a **user-plane** number. 1M SIP TPS is many signaling hosts + kernel/DPDK only on UP.
