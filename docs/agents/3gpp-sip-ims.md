# 3GPP SIP / IMS (Elisa lab Core)

Elisa implements **lab IMS Core** roles (feature flags). Still **not** an SBC. See [`ims-core.md`](ims-core.md).

## Spec anchors

Local Rel-16 cache (PDF + MD extract): [`../3gpp/`](../3gpp/README.md). Roadmap matrix: [`ims-protocol-roadmap.md`](ims-protocol-roadmap.md).

| Area | Spec | Elisa role |
|------|------|------------|
| Baseline SIP | RFC 3261 | Transaction + dialog SM — never break |
| IMS call control | 3GPP TS 24.229 | P/I/S-CSCF Gm/Mw; Path / Service-Route; P-* |
| IMS architecture | 3GPP TS 23.228 | CSCF + HSS-lite + ISC to FS MRF |
| Cx | TS 29.228 / 29.229 | UAR/MAR/SAR/LIR via `ra-diameter` |
| Auth | TS 33.203 | IMS AKA on REGISTER |
| Charging Rf/Ro | TS 32.260 / 32.299 | Rf stub + CGRates; Gx/Gy **Never** |
| VoLTE / VoNR media | GSMA IR.92 (+ ICE/TURN) | Media on FreeSWITCH / TURN — not Elisa RTP |
| 5G access (context) | TS 23.501 / 23.502 | AMF/SMF/UPF **external**; SIP still Gm → P-CSCF |

Call flows (5G → CSCF → FreeSWITCH ISC/xml_curl/ESL/RTP): [`ims-core.md`](ims-core.md).

## Preserve on SIP path (when present)

- `P-Asserted-Identity`, `P-Preferred-Identity`
- `P-Access-Network-Info`, `Cellular-Network-Info`
- `P-Charging-Vector`, `P-Charging-Function-Addresses` — lab stamps/preserves ICID via `PChargingVectors` (P/I/S INVITE); CDR column `icid`
- `P-Visited-Network-ID`, `P-Called-Party-ID`, `P-Associated-URI`
- `Feature-Caps`, `P-Early-Media`, `Path`, `Service-Route`
- `Require` / `Supported` / `Proxy-Require` (`100rel`, `precondition`, `sec-agree`)

RA: `com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames`.

## Out of scope

- SBC / media border / RTP through Elisa
- Full PCRF Rx, MGCF/BGCF in early phases
- AMF/SMF/UPF inside Elisa (5GC is a separate deploy)
- Claiming VoLTE/VoNR certification without interconnect tests
