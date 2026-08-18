# EPC SLEE layering (RA / SBB / Profile / CMP)

Transport opens sockets. SBBs run procedures. Profiles are provisioned. CMP is in-flight.

```text
MME/eNB ──UDP──► gtpv2c-ra ──Gtpv2MessageEvent──► GtpGatewaySbb
UPF      ──UDP──► pfcp-ra   ──PfcpMessageEvent───► GtpGatewaySbb
MME/CSCF ─SCTP/TCP► diameter-ra ──DiameterRequestEvent──► DiameterGatewaySbb
                                      │
                                      ├─ Profile  EpsSubscriber / ApnProfile
                                      └─ CMP      IMSI, APN, TEID, SEID, Session-Id
```

| Layer | Owns | Does not own |
|-------|------|----------------|
| **RA** (`gtpv2c-ra`, `pfcp-ra`, `diameter-ra`) | UDP/SCTP/TCP bind, codec, Echo / Association / CER-CEA / DWR, duplicate **response** replay (TS 29.274 §7.6) | Create Session, AIR/ULR, MAR/SAR, IPAM, charging |
| **SBB** (`GtpGatewaySbb`, `DiameterGatewaySbb`) | Procedures. Sends sealed commands (`SendGtpv2Message`, `PfcpProgramSession`, `SendDiameterAnswer`) | Sockets. Never `DatagramSocket` / Diameter stack |
| **Profile** (`EpsSubscriber`, `ApnProfile`) | Provisioned IMSI/MSISDN/default APN and APN/PDN/pool | Ki/OPc/SQN, live TEID, charging octets, peer liveness |
| **CMP** | In-flight correlate (Session-Id, TEID, SEID, generation) restored after passivate | Provisioned subscriber rows |

## Link truth

LISTEN / `raActive()` is local lifecycle only.

| Plane | Live evidence |
|-------|----------------|
| Diameter | `isPeerReady()` (CER/CEA + watchdog) |
| GTP-C | Echo **Response** |
| PFCP | Association Setup Response or Heartbeat Response |

## Host wiring

`EpcBootstrap.attachTo(MicroSleeContainer)` registers the RAs and maps:

- `DiameterRequestEvent` → `DiameterGatewaySbb` (demux on `applicationId`)
- `Gtpv2MessageEvent` / `PfcpMessageEvent` → `GtpGatewaySbb`

`EpcBootstrap.start(RaBootstrapPort)` is the RA-only test path (no SBB).

SIP stays on Elisa `sip-servlet-ra`. `epc-host` must not depend on it.

RAs live in micro-jainslee `vendor-ras/` (`com.microjainslee:ra-gtpv2c`, `ra-pfcp`, `ra-diameter`). Procedures stay in this tree.
