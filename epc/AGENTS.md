# AGENTS.md — epc (4G Full MVNO EPC, inside sip-freeswitch)

**Tree:** `ethiopia-working-dir/worktrees/voice-service/sip-freeswitch/epc`

Same **private** repo as Elisa (`nhanth87/sip-freeswitch` only). Never public; never Digicom-ET remotes.

## Role

Pilot-scale **4G deep/Full MVNO core**: MVNO owns **HSS/AuC + SGW + PGW**. Host MNO owns RAN/eNodeB and **MME**. DRA/DRE/STP are **out of this tree** — expose standards-compliant peers only.

**Elisa remains the IMS Core.** Do **not** put GTP-U, S11, S6a, PFCP, or PCEF inside the Elisa process. One git repo ≠ one JVM.

- **SIP** → vendor `ra-sip-servlet` in Elisa only (`sip-servlet-ra`). epc-host must not depend on it.
- **Diameter** S6a/Cx/Gx/Gy → vendor `ra-diameter` (`diameter-ra`). Live = `isPeerReady()` (CER/CEA), never LISTEN.
- **HSS data** → Postgres `postgres-epc` (`JdbcHssRepository`, atomic `UPDATE sqn … RETURNING`). Ki/OPc in HSM/`SecretStore`, not in SQL.

## Clustering (micro-jainslee)

Mirror Elisa: one `epc-host` image → N services via `EPC_ROLE_HSS` / `EPC_ROLE_SGWC` / `EPC_ROLE_PGWC`.

- Infinispan / session cache name **`epc-cluster`** — never join `elisa-cluster`.
- TEID high byte = `EPC_CLUSTER_NODE_INDEX` so N SGW-C/PGW-C slices do not collide.
- HSS SQN reserved atomically on the subscriber row (not heap-only pending vectors).
- Dual-write: hot map first, Postgres checkpoint **off** the GTP receive loop.
- Link UP = CEA / GTP Echo Response / PFCP Association or Heartbeat. LISTEN ≠ live.

## SLEE layering (mandatory)

Connection lives in the RA. Procedures live in the SBB. Provisioned data is Profile. In-flight state is CMP.

| Plane | RA (socket/codec/keepalive) | SBB (procedure) | Profile | CMP |
|-------|-----------------------------|-----------------|---------|-----|
| Diameter S6a/Cx | `diameter-ra` (CER/CEA/DWR) | `DiameterGatewaySbb` | `EpsSubscriber` | Session-Id, applicationId |
| GTPv2-C | `gtpv2c-ra` (UDP, Echo, §7.6 replay) | `GtpGatewaySbb` | `ApnProfile` | IMSI, APN, TEID, SEID |
| PFCP | `pfcp-ra` (UDP, Association/Heartbeat) | `GtpGatewaySbb` | — | SEID |
| SIP | Elisa `sip-servlet-ra` only | Elisa CSCF SBBs | Elisa | Elisa |

Detail: [`docs/agents/epc-slee.md`](../docs/agents/epc-slee.md).

## JDK

**Java 25 only** (mise `zulu-25`). Control plane is Java. User plane is **not** JVM (Linux GTP / VPP / optional NextEPC commercial).

## Dist

`./tools/package-dist-epc.sh` → `dist/epc/run.sh`. Never clobber operator `configs/`. Dedicated DB `epc`, never `elisa`.

## Prove the artifact

Green `mvn test` is not a live EPC. Do not claim attach, gigabit, HA, or VoLTE until wire proof + soak + charging reconciliation.

## Authorship

Commits: nhanth87 / Tran Nhan only. No AI attribution.
