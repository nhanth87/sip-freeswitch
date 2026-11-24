# PLAN — EPC P1: complete HSS + PCRF (3GPP) + open5gs interop

Orchestration/completion plan. Sources of truth:
- `GAP-hss-pcrf.md` (S6a/Cx/Gx/Rx vs Open5GS hss + pcrf)
- `GAP-sgw-pgw.md` (S11/S5-C/Sxa PFCP vs Open5GS sgwc + sgwu)
- This file (additional P0 blocker + build order + assignments)

## 1. Verdict

**Not yet production P1.** The service logic (AKA, SQN resync, subscription decode,
policy decision, PCC caching) is largely present, but **every Diameter wire answer
is flat** and **inbound application AVPs are dropped** by the RA. An Open5GS MME /
S-CSCF / PCEF cannot consume our AIA/ULA/MAA/CCA, and our HSS cannot read the
MME's AIR/ULR. Attach (S6a) and IMS auth (Cx) and PCC (Gx) all fail on the wire.

## 2. P0 blocker (found during plan, root cause of both reports' "grouped AVP" items)

`ra-diameter` (vendor `com.microjainslee:ra-diameter`) uses a flat
`Map<Integer,String>` AVP channel end-to-end:

| Surface | Current | Consequence |
|---|---|---|
| `SendDiameterAnswer/Request.avps` | `Map<Integer,String>` | cannot carry grouped/octet/uint32 |
| `CorsacDiameterTransport.addAvps()` | `addAvp(code, String, false)` | no V-bit (vendor 10415), no M-bit, no groups, no octets |
| `CorsacEventBridge.toEvent()` | only Session-Id/Origin/User-Name | **inbound app AVPs (RAND/AUTS/Subscription-Id/…) are dropped** |

The Corsac stack itself is a full Diameter stack (it exposes
`getOptionalAvps()` — `Map<DiameterAvpKey(code,vendor), List<DiameterUnknownAvp>>`
— and JDiameter `MessageParser` for outbound encode supports
`addGroupedAvp(code, vendorId, m, p)` + vendor-aware `addAvp`).

**Fix (self-contained, ra-diameter only):** introduce a structured AVP tree
(`DiameterAvp` + list) at the RA↔SBB boundary; encode grouped/vendor/typed AVPs
through JDiameter; decode inbound optional-avps in `CorsacEventBridge`.

## 3. Build order (dependency graph)

```
P0  ra-diameter structured AVP layer            (lead)
        │  (unblocks everything on the wire)
        ├──► B  epc-hss:  grouped S6a/Cx answers + exp-results
        └──► C  epc-pcrf/pcef: grouped Gx/Rx + server-initiated
D  (independent) docker-compose wiring of open5gs MME + SGW-U/PGW-U peers
        └──► E  (late) open5gs attach smoke + tshark
```

- S5-C wire + PFCP Sxa encode (GAP-sgw-pgw blockers 1/2/6) are **larger, later**
  and out of scope for *this* HSS/PCRF completion; documented, not claimed.
- Postgres + Infinispan are **already wired**: `JdbcHssRepository` + `CachingHssRepository`
  (read-through ISPN, PG = SoT for SQN/writes), `JdbcPolicyRepository` + `IspnPccCache`,
  Flyway migrations V1..V4. B/C verify and extend, do not re-invent.

## 4. Workstreams (parallel)

- **P0 (lead):** ra-diameter structured AVP layer (this plan). Build + `mvn install`.
- **B (agent):** epc-hss 3GPP-complete: AIA `Authentication-Info(1490)→
  E-UTRAN-Vector(1491){RAND,XRES,AUTN,KASME}`, ULA grouped `Subscription-Data(1400)`
  with TBCD MSISDN(701)+AMBR(1435), MAA `SIP-Auth-Data-Item(612)`+`Public-Identity(601)`,
  SAA `Charging-Information(618)→Primary-CCF-Name(620)`, UAA experimental
  2002/2003, Auth-Session-State(277). Confirm PG+ISPN path.
- **C (agent):** epc-pcrf/pcef 3GPP-complete: CCA grouped
  `Charging-Rule-Install(1001)→Charging-Rule-Definition(1003)→{Name,Flow-Information(1058),
  Flow-Status(1056),QoS-Information(1016)→QCI(1026)+ARP(1014)}`, Default-EPS-Bearer-QoS(1023),
  APN-AMBR QoS-Information; Rx AAR→AAA IP-Can-Type(1027)/RAT-Type(1032); server-init
  Gx RAR / Rx ASR / Cx RTR/PPR / S6a CLR/IDR via `SendDiameterRequest`.
- **D (agent, already running):** open5gs MME + SGW-U/PGW-U in docker-compose `epc` profile.

## 5. Proving it (open5gs interop)

1. `mvn -q test` in `epc/` (unit) + ra-diameter module test.
2. `./tools/package-dist-epc.sh` → `dist/epc/`.
3. `docker compose --profile epc up -d --build`; open5gs mme does S6a AIR→AIA /
   ULR→ULA against epc-hss; confirm ULA-Flags + Auth-Info grouped on tshark.
4. Cx MAR→MAA grouped (SIP-Auth-Data-Item) against open5gs built as S-CSCF peer;
   Gx CCR→CCA grouped Charging-Rule-Install against open5gs PGW/PCRF.