# PICS — Release 14 profile (what we implement)

Mandatory for the data-only pilot. Optional items stay listed so they cannot be silently assumed.

## S6a (TS 29.272) — HSS

| Command | Pilot | Notes |
|---------|-------|-------|
| AIR/AIA | M | EPS-AKA vectors: RAND, XRES, AUTN, KASME |
| ULR/ULA | M | subscription + serving MME |
| CLR/CLA | M | cancel old MME |
| PUR/PUA | M | purge |
| IDR/IDA | M | push subscription change |
| DSR/DSA | M | delete subscriber data |
| RSR/RSA | M | reset |
| NOR/NOA | M | notification |
| AUTS resync | M | SQN recovery |

## GTPv2-C (TS 29.274)

| Message | SGW-C | PGW-C |
|---------|-------|-------|
| Create Session | M | M |
| Modify Bearer | M | M |
| Delete Session | M | M |
| Downlink Data Notification | M | — |
| Release Access Bearers | M | — |
| Create/Update/Delete Bearer | O | O (dedicated bearer / VoLTE) |
| Echo / Recovery | M | M |

## PFCP (TS 29.244)

| Message | Pilot |
|---------|-------|
| Association Setup / Heartbeat | M |
| Session Establishment / Modification / Deletion | M |
| Session Report | M |
| PDR / FAR / QER / URR | M (default bearer) |

## Explicitly out of this freeze

- MME / S1AP / NAS
- DRA/DRE/STP implementation
- Java GTP-U
- 5GC N1/N2/N4
- VoLTE dedicated bearer until Rx is proven
