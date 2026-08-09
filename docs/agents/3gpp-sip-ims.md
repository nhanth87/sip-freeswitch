# 3GPP SIP / IMS (Elisa lab Core)

Elisa implements **lab IMS Core** roles (feature flags). Still **not** an SBC. See [`ims-core.md`](ims-core.md).

## Spec anchors

| Area | Spec | Elisa role |
|------|------|------------|
| Baseline SIP | RFC 3261 | Transaction + dialog SM — never break |
| IMS call control | 3GPP TS 24.229 | P/I/S-CSCF Gm/Mw; Path / Service-Route; P-* |
| IMS architecture | 3GPP TS 23.228 | CSCF + HSS-lite + ISC to FS MRF |
| Cx | TS 29.228 / 29.229 | UAR/MAR/SAR/LIR via `ra-diameter` |
| Auth | TS 33.203 | IMS AKA on REGISTER |
| VoLTE media | GSMA IR.92 (+ ICE/TURN) | Media on FreeSWITCH / TURN — not Elisa RTP |

## Preserve on SIP path (when present)

- `P-Asserted-Identity`, `P-Preferred-Identity`
- `P-Access-Network-Info`, `Cellular-Network-Info`
- `P-Charging-Vector`, `P-Charging-Function-Addresses`
- `P-Visited-Network-ID`, `P-Called-Party-ID`, `P-Associated-URI`
- `Feature-Caps`, `P-Early-Media`, `Path`, `Service-Route`
- `Require` / `Supported` / `Proxy-Require` (`100rel`, `precondition`, `sec-agree`)

RA: `com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames`.

## Out of scope

- SBC / media border / RTP through Elisa
- Full PCRF Rx, MGCF/BGCF in early phases
- Claiming VoLTE certification without interconnect tests
