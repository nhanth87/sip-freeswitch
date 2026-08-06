# 3GPP SIP / IMS readiness (signaling only)

Elisa stays a **SIP-only** edge (not SBC, not CSCF). This note is the 4G→5G→6G **signaling** extension path.

## Spec anchors

| Area | Spec | Elisa role |
|------|------|------------|
| Baseline SIP | RFC 3261 | Keep transaction + dialog SM correct — never break |
| IMS call control | 3GPP TS 24.229 | Preserve/understand P-* / option tags on SIP hops |
| IMS architecture | 3GPP TS 23.228 | Out of tree: CSCF/HSS live elsewhere |
| VoLTE media | GSMA IR.92 (+ ICE/TURN here) | Media on FreeSWITCH / TURN — not Elisa RTP |
| VoNR | 5G IMS over SIP (same 24.229 family) | Same SIP surface; access info in P-Access-Network-Info / Cellular-Network-Info |
| 6G | Evolve same SIP/IMS control plane | Do not invent parallel call control in Elisa |

## Preserve on SIP path (when present)

Whitelist mindset (no arbitrary header forward):

- `P-Asserted-Identity`, `P-Preferred-Identity`
- `P-Access-Network-Info`, `Cellular-Network-Info`
- `P-Charging-Vector`, `P-Charging-Function-Addresses`
- `P-Visited-Network-ID`, `P-Called-Party-ID`, `P-Associated-URI`
- `Feature-Caps`, `P-Early-Media`
- `Require` / `Supported` / `Proxy-Require` (e.g. `100rel`, `precondition`, `sec-agree`)
- Security-Agreement headers when UE–IMS IPsec is in play

RA constants: `com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames` (ra-sip-servlet).

## Out of scope here

- Building an SBC or media border
- Implementing P/I/S-CSCF or Diameter Cx/Sh/Rx inside sip-freeswitch
- Claiming VoLTE/VoNR certification without interconnect tests

## Ingress

**nginx** later fronts admin HTTP (and optionally SIP TCP/TLS stream). SIP **state** stays in Elisa; nginx must not rewrite dialog-critical headers.
