# EPC plane (monorepo, separate process)

Same git repo as Elisa; **not** the Elisa JVM. micro-jainslee clustering mirrors Elisa: one image → N services by role flag, Infinispan `DIST_SYNC` on `epc-cluster`, PG dual-write off the GTP hot path, link-status = peer truth.

```text
Host eNB ──S1-MME── Host MME ──S6a──► DRA/DRE (external) ──► epc-hss
              │
              └──S11──► epc-sgwc ──Sxa──► sgwu ◄──S1-U── Host eNB
                           │                 │
                           └──S5-C──► epc-pgwc └──S5-U──► pgwu ──SGi── PDNs
                                        │                    │
                                   Gx/Gy/Gz               IMS APN → Elisa P-CSCF
```

| Service | Image | Flags | Planes |
|---------|-------|-------|--------|
| `epc-hss` | epc-host | `EPC_ROLE_HSS` | S6a + Cx (dedicated `postgres-epc`) |
| `epc-sgwc` (×N) | **same** | `EPC_ROLE_SGWC` | S11/S5 GTPv2-C + Sxa PFCP |
| `epc-pgwc` (×N) | **same** | `EPC_ROLE_PGWC` | S5 + Sxb + PCEF + IPAM |
| `sgwu` / `pgwu` | non-JVM | n/a | Linux GTP baseline (1:1 with C node) |

Cluster: `EPC_CLUSTER_NODE_ID`, `EPC_CLUSTER_ISPN_MODE=DIST_SYNC`, `EPC_CLUSTER_ISPN_NAME=epc-cluster` (never `elisa-cluster`). TEID high byte = `EPC_CLUSTER_NODE_INDEX`.

Cx seam (Elisa unchanged): `deploy/elisa/roles/scscf-cx-epc.env` sets `SF_IMS_HSS=false` + `SF_IMS_CX_DEST_HOST=epc-hss`. Lab default stays HSS-lite.

No VoLTE QoS, HA, or throughput claim without wire proof. NextEPC AccUPF 30 Gbit/s is 5G downlink-only — not this gate.

## RA locks

- SIP (Gm to P-CSCF) → Elisa **`sip-servlet-ra`** only. `epc-host` bans `ra-sip-servlet`.
- Diameter S6a/Cx/Gx/Gy → vendor **`diameter-ra`** (`isPeerReady()`). No second Diameter stack.
- Matrix: [`ra-matrix.md`](ra-matrix.md). SLEE split: [`epc-slee.md`](epc-slee.md). 10 Gbit/s on 64C/128G: [`epc/docs/agents/capacity-64c-10g.md`](../../epc/docs/agents/capacity-64c-10g.md).
