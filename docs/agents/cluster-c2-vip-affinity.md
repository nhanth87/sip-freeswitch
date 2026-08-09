# Cluster C2 — VIP / LB + Sofia profile affinity

## Goal

N Elisa nodes + N FreeSWITCH nodes without sticky-session hacks on every INVITE.
C1 (Infinispan dialog checkpoints) + C3 (paired ESL) assume **signaling affinity** so a dialog’s Elisa and its FS pair stay aligned.

## Locked shape (grill)

```text
UA ── VIP / L4 LB ──► Elisa-1 .. Elisa-N
                         │ paired ESL (sf_fs_pair)
                         ▼
                    FreeSWITCH-1 .. FreeSWITCH-N
                         │ Sofia profile affinity
                         ▼
                    media / ICE / TURN
```

## VIP / LB (SIP)

| Layer | Role |
|-------|------|
| **VIP** | Single SIP contact for UAs (`sip:vip:5060`) |
| **L4 LB** | TCP/TLS (and UDP if supported) hash on source IP **or** Call-ID when available |
| **Affinity** | Prefer same Elisa for mid-dialog (re-INVITE, BYE, ACK) — Call-ID hash or source IP |

UDP SIP load-balancing is weaker than TCP/TLS; lab can pin UA → one Elisa. Production: prefer SIP over TCP/TLS behind the VIP.

Elisa does **not** own the VIP — nginx/stream or an L4 appliance does. Elisa keeps the SIP transaction/dialog state machine on the node that accepted the INVITE.

## Sofia profile affinity (FreeSWITCH)

| Rule | Meaning |
|------|---------|
| **Pair** | Elisa-i ESL primary → FS-i (`sf_fs_pair.priority=0`) |
| **Failover** | Same Elisa → FS-j list by priority (`EslFailoverService`) |
| **Profile** | Each FS exposes a Sofia profile bound to that node’s SIP IP (e.g. `internal@fs-i`) |
| **Trunk URI** | Elisa `sip.trunk.freeswitch-uri` (or per-tenant trunk) points at the **paired** FS profile, not a floating VIP on FS |

Do **not** load-balance Elisa trunks across random FS nodes without updating the pair table — media and ESL events would diverge.

## Config knobs

| Key | Purpose |
|-----|---------|
| `sf.cluster.node-id` | This Elisa id (`elisa-1`) — must match `sf_fs_pair.elisa_node_id` |
| `sf_cluster_node` / `sf_fs_pair` | Topology rows (Flyway V3) |
| `sf.cluster.infinispan.mode` | `LOCAL` lab; `DIST_SYNC` multi-Elisa |
| `sf.esl.*` | Fallback when pair table empty |

## Ops checklist

1. Insert `sf_cluster_node` rows for each Elisa + FS (`kind=ELISA|FS`, `address=esl://host:8021` for FS).
2. Insert `sf_fs_pair` (elisa → fs, priority 0 primary, 1+ failover).
3. Point VIP → Elisa SIP ports; keep mid-dialog affinity.
4. Point each Elisa trunk URI at its primary FS Sofia profile.
5. Enable `DIST_SYNC` only when ≥2 Elisa share the Infinispan cluster name.

## IMS extension (grill I7)

| Plane | Sticky |
|-------|--------|
| Registration | `ims_binding.home_scscf_node` + Infinispan `RegistrationHomeStore` |
| Dialog | `DialogCheckpointStore` on home S-CSCF |
| I-CSCF LIR | Returns home S-CSCF URI for IMPU |
| FS pair | Affinity to S-CSCF node that owns the dialog |

See [`ims-core.md`](ims-core.md).
