# Elisa IMS feature map (inventory)

Living checklist vs lab IMS Core. Deploy shape: [`multi-service-deploy.md`](multi-service-deploy.md). Specs: [`3gpp-sip-ims.md`](3gpp-sip-ims.md), [`ims-core.md`](ims-core.md). Protocol matrix + Clearwater gap: [`ims-protocol-roadmap.md`](ims-protocol-roadmap.md). Cached 3GPP PDFs/MD: [`../3gpp/`](../3gpp/README.md).

| Area | Status | Notes |
|------|--------|-------|
| P/I/S-CSCF SBBs | **Lab** | `PcscfProxySbb`, `IcscfRoutingSbb`, `ScscfRegisterSbb`, `ScscfInviteSbb` |
| Role flags / split services | **Lab** | `sf.ims.role.*` + compose overlay |
| HSS-lite + AKA + Cx | **Lab** | local HSS or Diameter RA |
| REGISTER / INVITE MO–MT | **Lab** | TS 24.229 unit tests |
| ISC → FreeSWITCH | **Lab** | iFC-lite + `sf_sip_trunk` / `IscAsRouter` |
| SIP trunk registry | **Lab** | Flyway V3 `sf_sip_trunk`; `/admin/trunks`; props fallback |
| Call session dual-write | **Lab** | ISPN + `sf_call_session` + Profile mirror |
| CMP / N–N ISPN | **Lab** | Shared `ElisaInfinispanManager`; DIST_SYNC knobs; CMP = live INVITE SoT |
| JAINSLEE Profile | **Lab (JVM-local)** | `ImsSubscriberProfile` / `CallSessionProfile` — not failover SoT |
| CDR RestComm-shaped | **Lab** | + `sip_call_id` / `msisdn` / `icid` |
| Charging / Rf / Ro / Gx | **Lab (flagged)** | Master `sf.ims.feature.charging` OFF; SessionS + Rf + dual-write ready; Gx/Gy/PFCP **No** — [`production-p0.md`](production-p0.md) |
| C1–C3 HA | **Lab** | Shared ISPN cluster-name + VIP + FS pair |
| ICE/TURN policy | **Lab** | no RTP through Elisa |
| Tenants / ACL / admin HTMX | **Lab** | profiles, sessions, CDR, trunks; IMS admin shows peerReady+eslLive |
| Presence / full MMTel | **No** | iFC / `sf.ims.feature.mmtel` token only |
| ENUM / Chronos | **No** | `sf.ims.feature.enum` stub off |
| PCRF Rx, MGCF/BGCF | **No** | out of early phases |
| 5GC AMF/SMF/UPF | **No** | external; Gm still to P |

Clearwater name map (not product parity): Bono≈P+nginx · Sprout≈I/S · Homestead≈HSS-lite · Ralf≈Rf/SessionS flagged · Chronos=absent.
