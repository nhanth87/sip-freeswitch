# Resource Adaptor matrix (Elisa IMS + EPC)

Transport lives **only** in RAs. Apps/SBBs send sealed commands; they do not open SIP, Diameter, GTP, or PFCP sockets.

| Plane | RA | Artifact | Process | Status |
|-------|----|----------|---------|--------|
| SIP Gm/Mw/ISC | **`sip-servlet-ra`** | vendor `ra-sip-servlet` | **Elisa only** | Locked. `SipServletRaEndpoint` in `ElisaBootstrap.wireSipRa()`. |
| Diameter Cx | **`diameter-ra`** | vendor `ra-diameter` | Elisa (client) / `epc-hss` (server) | Locked. `isPeerReady()` = CER/CEA + watchdog. |
| Diameter S6a | **`diameter-ra`** | vendor `ra-diameter` | `epc-hss` | Locked. App-Id `16777251`. Same RA, different application. |
| Diameter Gx/Gy/Rf | **`diameter-ra`** | vendor `ra-diameter` | `epc-pgwc` / Elisa Rf | Locked. Do not invent a second Diameter stack. |
| GTPv2-C S11/S5 | **`gtpv2c-ra`** | vendor `ra-gtpv2c` | `epc-sgwc` / `epc-pgwc` | Vendor RA. Echo / codec / UDP only. |
| PFCP Sxa/Sxb | **`pfcp-ra`** | vendor `ra-pfcp` | SGW-C / PGW-C | Vendor RA. Association / Heartbeat / UDP only. |
| FreeSWITCH ESL | **`freeswitch-ra`** | vendor `ra-freeswitch` | Elisa S-CSCF | Vendor RA. Not SIP. |
| GTP-U S1-U/S5-U | **none in JVM** | Linux GTP / VPP / NextEPC UP | `sgwu` / `pgwu` | Java never forwards packets. |

## Hard locks

- **SIP never in `epc-host`.** Maven enforcer bans `com.microjainslee:ra-sip-servlet` on the EPC reactor host. IMS APN SIP still lands on Elisa P-CSCF via `sip-servlet-ra`.
- **Diameter never a sidecar codec-only stack.** `S6aMessage` is the application map; wire is always `diameter-ra` (`SendDiameterRequest` / `SendDiameterAnswer`).
- LISTEN ≠ live. Diameter live = `DiameterRaEndpoint.isPeerReady()`. GTP live = Echo Response. PFCP live = Association/Heartbeat.

## SLEE split

Apps/SBBs never open SIP, Diameter, GTP, or PFCP sockets. See [`epc-slee.md`](epc-slee.md).

## Proposed promotions (do not implement SIP/Diameter clones)

1. Elisa **Rx** (TS 29.214) on the same `diameter-ra` — required before any VoLTE QCI 1 claim. Not a new RA.
