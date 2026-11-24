# Host MNO NNI contract — Release 14 freeze

**Status:** frozen design. Commercial signature with the host is an operator gate; this file is the technical contract we will not silently widen.

**3GPP freeze:** Release 14 (CUPS TS 23.214, PFCP TS 29.244, EPS TS 23.401, GTPv2-C TS 29.274, GTP-U TS 29.281, S6a TS 29.272).

**Ownership:** host = RAN/eNodeB + MME. MVNO = HSS/AuC + SGW-C/U + PGW-C/U. DRA/DRE/STP = separate project.

## Hard stop

If the host offers only roaming-style **S8**, the host SGW stays in path and **this project's SGW cannot be used**. Proceed only if all of the following are exposed.

## Required interfaces

| Interface | From | To | Protocol | Spec | Transport |
|-----------|------|----|----------|------|-----------|
| S6a | Host MME | MVNO HSS | Diameter App-ID 16777251 | TS 29.272 | SCTP (preferred) via DRA/DRE |
| S11 | Host MME | MVNO SGW-C | GTPv2-C | TS 29.274 | UDP/IP |
| S1-U | Host eNodeB | MVNO SGW-U | GTPv1-U | TS 29.281 | UDP/IP |
| S5 | MVNO SGW | MVNO PGW | GTPv2-C + GTP-U | TS 29.274 / 29.281 | UDP/IP (intra-PLMN) |

Optional later: S8 (roaming), S9, Gx, Gy, Gz, Cx (IMS), Rx (VoLTE QoS).

## Diameter / DRA boundary

- Native LTE Diameter **does not** traverse an STP.
- HSS advertises realm `epc.mncXXX.mccYYY.3gppnetwork.org` and Origin-Host `hss.epc.<operator>`.
- Peer allowlist + application-id filter only. Watchdog, overload AVPs, restart/recovery required.
- DRA/DRE implementation is out of tree; this project exposes redundant Diameter peers.

## Identity and numbering (fill before interconnect)

| Item | Value | Owner |
|------|-------|-------|
| MCC / MNC | _TBD_ | MVNO numbering |
| IMSI range | _TBD_ | MVNO |
| MSISDN range | _TBD_ | MVNO |
| PLMN ID (S6a SN id) | encoded per TS 24.301 | MVNO |
| Default APN | `internet` | MVNO |
| IMS APN | `ims` | MVNO (VoLTE phase) |
| UE-AMBR default | 1 Gbit/s DL / 1 Gbit/s UL (pilot) | MVNO |
| APN-AMBR default | 1 Gbit/s | MVNO |

## APN / IP plan (pilot)

| APN | PDN type | Pool | VRF | P-CSCF |
|-----|----------|------|-----|--------|
| internet | IPv4v6 | 10.64.0.0/16 + 2a00:epc:1::/48 | internet | n/a |
| ims | IPv4v6 | 10.65.0.0/16 + 2a00:epc:2::/48 | ims | Elisa P-CSCF |

Static UE IP is supported per subscriber record; otherwise PGW-local allocation (TS 29.061). External DHCP is out of pilot.

## Security NNI

- IPsec/NDS-IP (TS 33.210) or physically isolated interconnect.
- GTP IE validation, TEID scoped by receiver IP, anti-spoofing on SGi.
- No IMSI/Ki/OPc/KASME in logs.

## Failure / SLA (pilot)

| Event | Required behaviour |
|-------|--------------------|
| HSS restart | S6a Recovery / RSR; MME re-registers |
| SGW-C restart | GTP Recovery IE; stale sessions deleted or restored (TS 23.007) |
| SGW-U / PGW-U restart | PFCP association heartbeat + session report |
| DRA unreachable | Diameter peer not-ready immediately; no silent 2xxx |

## Sign-off checklist (host)

- [ ] S6a routed to MVNO HSS via DRA/DRE
- [ ] S11 to MVNO SGW-C
- [ ] S1-U to MVNO SGW-U
- [ ] Transport security agreed
- [ ] APN/PLMN selection on host MME
- [ ] Failure SLA signed
- [ ] LI/retention RACI signed

Until every box is checked, do not claim a Full MVNO data service.
