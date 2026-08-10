# Elisa IMS feature map (inventory)

Living checklist vs lab IMS Core. Deploy shape: [`multi-service-deploy.md`](multi-service-deploy.md). Specs: [`3gpp-sip-ims.md`](3gpp-sip-ims.md), [`ims-core.md`](ims-core.md).

| Area | Status | Notes |
|------|--------|-------|
| P/I/S-CSCF SBBs | **Lab** | `PcscfProxySbb`, `IcscfRoutingSbb`, `ScscfRegisterSbb`, `ScscfInviteSbb` |
| Role flags / split services | **Lab** | `sf.ims.role.*` + compose overlay |
| HSS-lite + AKA + Cx | **Lab** | local HSS or Diameter RA |
| REGISTER / INVITE MO–MT | **Lab** | TS 24.229 unit tests |
| ISC → FreeSWITCH | **Lab** | iFC-lite string match; ESL correlate |
| Call session dual-write | **Lab** | ISPN + `sf_call_session` |
| CDR RestComm-shaped | **Lab** | + `sip_call_id` / `msisdn` / `icid` |
| Charging / Rf / Ro / Gx | **Lab ICID** | ICID + CDR only; Rf/Ro deferred (P1/P2); Gx/Gy/PFCP **No** — [`ims-core.md`](ims-core.md) § Charging |
| C1–C3 HA | **Lab** | ISPN + VIP + FS pair |
| ICE/TURN policy | **Lab** | no RTP through Elisa |
| Tenants / ACL / admin HTMX | **Lab** | profiles, sessions, CDR |
| Presence / full MMTel | **No** | iFC token only |
| ENUM / Ralf / Chronos | **No** | — |
| PCRF Rx, MGCF/BGCF | **No** | out of early phases |
| 5GC AMF/SMF/UPF | **No** | external; Gm still to P |

Clearwater name map (not product parity): Bono≈P+nginx · Sprout≈I/S · Homestead≈HSS-lite · Ralf/Chronos=absent.
