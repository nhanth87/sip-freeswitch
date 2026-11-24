# Open5GS HSS + PCRF vs. our EPC HSS/PCRF — GAP report

Date: discovery pass. Sources: local Open5GS clone (`loadtest/ims-tools/open5gs`) vs our Java
(`epc/epc-hss`, `epc/epc-pcrf`, `epc/epc-host`). Positive finding up front: our Diameter
**Application-Ids and command codes match Open5GS exactly**. Every gap is in **answer AVP
encoding** (missing grouped parents, missing AVP wrappers) and **which commands are actually
wired through `DiameterGatewaySbb` / `PcrfSbb`** — not in the protocol numbering.

Confirmed-identical identifiers:

| Element | Open5GS | Ours | Match |
|---|---|---|---|
| S6a app | `lib/diameter/s6a/dict.c:95` → 16777251 | `S6aCommand.java:14` 16777251 | OK |
| Cx app | `lib/diameter/cx/dict.c:154` → 16777216 | `CxCommand.java:12` 16777216 | OK |
| Gx app | `lib/diameter/gx/dict.c:153` → 16777238 | `GxHandler.java:35` 16777238 | OK |
| Rx app | `lib/diameter/rx/dict.c:154` → 16777236 | `RxHandler.java:21` 16777236 | OK |
| S6a AIR/AIA 318, ULR/ULA 316, PUR/PUA 321, CLR/CLA 317, IDR/IDA 319 | `lib/diameter/s6a/dict.c:342..621` | `S6aCommand.java` | OK |
| Cx UAR 300, LIR 302, MAR 303, SAR 301, RTR 304 | `lib/diameter/cx/dict.c:172..490` | `CxCommand.java` | OK |
| Gx CCR/CCA 272, RAR 258 | Gx dict | `GxHandler.java:36` CCR=272; `RxHandler.java:23` RA=258 | OK |
| Rx AAR 265, RAR 258, ASR 274, STR 275 | Rx dict | `RxHandler.java:22-25` | OK |

---

## 1. Open5GS message inventory

> `file:line` evidence from the local clone.

### S6a (HSS), `src/hss/hss-s6a-path.c` (app 16777251)

**AIR → AIA** (registry `hss_s6a_init` ~2294-2304; handler `hss_ogs_diam_s6a_air_cb` ~130). Answer AVPs (`air_cb` ~330-400):
- **Authentication-Info (1490)** grouped, then **E-UTRAN-Vector (1491)** grouped added as child, with leaves:
  RAND (1447), XRES (1448), AUTN (1449), KASME (1450); vector → auth-info → top-level answer (~348-395).
- **Auth-Session-State (277)** = NO_STATE_MAINTAINED (1) — `hss-s6a-path.c:~404-424`, also in error path.
- Success via `fd_msg_rescode_set(ans,"DIAMETER_SUCCESS")` (Result-Code **2001**); errors via **Experimental-Result**
  `ogs_diam_message_experimental_rescode_set` (5001 USER_UNKNOWN, 4181 AUTO-DATA-UNAVAIL, MISSING_AVP/INVALID_AVP_VALUE) — ~476-480.
- Vendor-Specific-Application-Id (`ogs_diam_message_vendor_specific_appid_set` ~448).

**ULR → ULA** (handler `hss_ogs_diam_s6a_ulr_cb` ~975). Answer AVPs:
- **Subscription-Data (1400)** grouped, `hss_s6a_avp_add_subscription_data(..., SUBDATA_ALL)` (hss-s6a-path.c:1237-1238); SUBDATA_ALL adds
  (hss-s6a-path.c:513+, masks `message.h:79-89`): MSISDN (701, TBCD ~537), A-MSISDN, Access-Restriction-Data, ODB,
  **Subscriber-Status (1424)**, **Network-Access-Mode (1417)**, **AMBR (1435)**→UL(516)/DL(517) (~620-644),
  Subscribed-RAU-TAU-Timer (1481), **APN-Configuration-Profile (1429)**→Context-Identifier(1423)=1,
  All-APN-Configurations-Included(1428), per-APN **APN-Configuration (1430)** (~659+).
- **Auth-Session-State (277)** (~1266-1285); **ULA-Flags (1406)** = MME_REGISTERED_FOR_SMS (~1287-1315);
  Vendor-Specific-Application-Id; **Supported-Features ×2** (Feature-List-ID 1 & 2, Vendor-Id 10415) (~1320-1490).
- Result: DIAMETER_SUCCESS / Experimental-Result (UNKNOWN_EPS_SUBSCRIPTION, USER_UNKNOWN, UNABLE_TO_DELIVER=3005).

**PUR → PUA** (registered hss-s6a-path.c:2320-2324): PUA success/Experimental-Result + Auth-Session-State.

**CLR (317)** — **server-initiated only** (`hss_s6a_send_clr`, hss-s6a-path.c:~1850-1930): User-Name, Destination-Host (mme_host), Destination-Realm, Auth-Session-State, Origin, **CLR-Flags** (REATTACH_REQUIRED|S6A_S6D_INDICATOR), **Cancellation-Type (1420)**.

**IDR (319)** — server-initiated (`hss_s6a_send_idr`, hss-s6a-path.c:~2060-2180): **IDR-Flags**, pushes Subscription-Data.

### Cx (HSS), `src/hss/hss-cx-path.c` (app 16777216)

**UAR → UAA** (handler ~60): answer carries **Server-Name (602)**; result is **Experimental-Result** app codes **FIRST_REGISTRATION (2002)** / **SUBSEQUENT_REGISTRATION (2003)** (`hss-cx-path.c:262-269`), *not* DIAMETER_SUCCESS.

**MAR → MAA** (handler ~390, build hss-cx-path.c:790-1010): **Public-Identity (601)**; **SIP-Number-Auth-Items (607)**=1;
**SIP-Auth-Data-Item (612)** grouped, leaves: SIP-Item-Number (613)=1, **SIP-Authentication-Scheme (608)**="Digest-AKAv1-MD5",
**SIP-Authenticate (609)**=RAND‖AUTN (32B), **SIP-Authorization (610)**=XRES, **Confidentiality-Key (625)**=CK, **Integrity-Key (626)**=IK.

**SAR → SAA** (handler ~1070, build hss-cx-path.c:1398-1490): **Server-Name (602)** echo; **User-Data (606)** only when NOT already available;
**Charging-Information (618)**→**Primary-CCF-Name (620)**="pcrf" (hss-cx-path.c:1426-1465).

**LIR → LIA** (handler ~1545): **Server-Name (602)**; error **SERVER_NAME_NOT_STORED** (hss-cx-path.c:1617).

### Gx (PCRF), `src/pcrf/pcrf-gx-path.c` (app 16777238)

**CCR → CCA** (answer build ~600-990): **CC-Request-Type (416)** / **CC-Request-Number (415)** echoed (335,351);
**Charging-Rule-Install (1001)**→**Charging-Rule-Definition (1003)**→{**Charging-Rule-Name (1005)**, **Flow-Information (1058)**→{Flow-Direction, Flow-Description(507)}, **Flow-Status (1056)**, **QoS-Information (1016)**→{QCI(1026), ARP(1014)→Priority-Level(1049), Pre-emption-Capability(1047), Pre-emption-Vulnerability(1048)}} (encode_pcc_rule_definition ~1680-1830);
**QoS-Information (1016)**→APN-AMBR-UL(1027)/DL(1028) (~676-720); **Default-EPS-Bearer-QoS (1023)**→QCI, ARP; Result DIAMETER_SUCCESS/Experimental-Result.
**Rating-Group is NOT emitted** (no `ogs_diam_gx_rating_group` in file).
RAR (258) server-initiated (`pcrf_gx_send_rar` ~1046): Charging-Rule-Install / **Charging-Rule-Remove (1002)** with **Charging-Rule-Name (1005)**.

### Rx (PCRF), `src/pcrf/pcrf-rx-path.c` (app 16777236)

**AAR → AAA** (build 488-522): **IP-Can-Type (1027)=3GPP-EPS**, **RAT-Type (1032)=EUTRAN**, DIAMETER_SUCCESS;
Experimental-Result IP_CAN_SESSION_NOT_AVAILABLE. **STR → STA** (~855). **ASR (274)** server-initiated carries **Abort-Cause (500)** (hss-rx:697).
---

## 2. Our coverage table

| Flow | Open5GS AVPs / behavior | Our handler | Verdict | Note |
|---|---|---|---|---|
| S6a AIR→AIA | nested **Auth-Info(1490)→E-UTRAN-Vector(1491)→{RAND,XRES,AUTN,KASME}**; Auth-Session-State(277); VSA-Id | `HssService.air` (HssService.java:57-64) **flat** RAND/XRES/AUTN/KASME top-level; NO 1490/1491; NO 277 | **MISSING** | MME finds no auth-info/vector wrapper → vector rejected. Top S6a gap. |
| S6a ULR→ULA | Subscription-Data(1400) grouped; MSISDN(701) TBCD; AMBR(1435)→UL/DL; ULA-Flags(1406); Supported-Features×2 | `HssService.ulr`→`S6aSubscriptionData.ulaAvps` (S6aSubscriptionData.java:54-61) flat JSON `1400`, `ul=..;dl=..`; NO 701, NO 1429 nested, NO 1406 | **MISSING** | Wire GBR/APN-profile absent for MME/PGW; ULA-Flags expected. |
| S6a PUR→PUA | success + Auth-Session-State(277) | `HssService.pur` (HssService.java:94-98) ok(PUA), Result-Code(268) | PARTIAL-OK logic / MISSING 277 | Command wired; no 277. |
| S6a CLR | server→MME (CLR-Flags, Cancellation-Type 1420) | `HssService.clr` handles **inbound** CLR (HssService.java:88-92); mapper maps it | N/A (opposite) | We answer inbound CLR/CLA; no server send path. |
| S6a IDR | server→MME (IDR-Flags, push sub-data) | `HssService` IDR→IDA ack only (HssService.java:33) | PARTIAL | Ack inbound only; no server push. |
| S6a NOTIFY (NOR) | **not implemented** in Open5GS | `HssService` NOR→NOA (HssService.java:36) | OK (superset) | Not a blocker. |
| Cx UAR→UAA | **Server-Name(602)**; Exp-Result **2002/2003**; VSA-Id | `CxService.uar`(60-77) ok+602; `DiameterGatewaySbb.cx`(…Sbb.java:137-151) returns Result-Code **2001** | PARTIAL | Gateway returns SUCCESS, not FIRST/SUBSEQUENT_REGISTRATION; only 602 leaf. |
| Cx MAR→MAA | Public-Identity(601); SIP-Number-Auth-Items(607)=1; **SIP-Auth-Data-Item(612)**→{Item(613), Scheme(608), Auth(609) RAND‖AUTN, Authorization(610), CK(625), IK(626)} | `CxService.mar`(80-106) flat 607/608/609/610/625/626, **NO 601, NO 612 wrapper, NO 613**; **NOT dispatched** — gateway has no `case 303` → 5001 | **MISSING + BLOCKED** | S-CSCF gets 5001 for MAR; no vector grouping. |
| Cx SAR→SAA | Server-Name(602) echo; User-Data(606); **Charging-Information(618)→CCF-Name(620)=pcrf** | `CxService.sar`(109-128) 602 + User-Data JSON; **NO Charging-Information(618)**, NO UDA handling | **MISSING** | No charging info in SAA; flat JSON not Cx User-Data XML. |
| Cx LIR→LIA | Server-Name(602) | `CxService.lir`(131-148) 602 | OK | Matches. |
| Cx RTR/PPR server-init | RTR dereg, PPR push | `CxService.rtr/ppr`(151-170) exist, **not wired** | UNWIRED | No HSS→S-CSCF send path. |
| Gx CCR→CCA | 416/415 echo; Charging-Rule-Install(1001)→Charging-Rule-Definition(1003)→{Name,Flow-Info(1058),Flow-Status(1056),QoS-Info(1016)→QCI/ARP}; APN-AMBR QoS-Info; Default-EPS-Bearer-QoS(1023) | `PcrfSbb.gx`(105-111)→`GxHandler.cca`(96-112): 268/416/415 + **flat string** Charging-Rule-Install / Default-EPS-Bearer-QoS; Usage-Monitoring string | **MISSING (encapsulation)** | Logic present; wire is flat strings, no grouped rule/flow/ARP. PCEF cannot parse. |
| Gx RAR | server→PCEF Charging-Rule-Remove(1002)+Name | `GxHandler` no RAR builder into answer; `PcrfSbb` answers only CCR | MISSING/UNWIRED | Server-initiated Gx RAR not emitted. |
| Rx AAR→AAA | IP-Can-Type(1027)=3GPP-EPS; RAT-Type(1032)=EUTRAN | `RxHandler.handleAar`(52-62): Result + AF-Application-Identifier only; NO IP-Can-Type/RAT-Type | PARTIAL | Missing IP-Can-Type/RAT-Type; heuristic QCI1/QCI5 binding. |
| Rx STR→STA | success | `RxHandler.handleStr`(94-99) | OK | Matches. |
| Rx RAR/ASR server-init | RAR-ReAuth(285); ASR Abort-Cause(500) | `RxHandler.buildRar/buildAsr`(126-139) exist, **not wired** to send | PARTIAL/UNWIRED | Builder logic only. |
| CER/CEA + DWR/DWA | freeDiameter transport; `fd_disp_app_support(app, vendor10415,1,0)` (hss-s6a:2329, hss-cx:1801, gx:1659, rx:1103) | `DiameterSeam` peer + `diameter-ra` isPeerReady=CEA | ASSUMED | `ra-diameter` must advert same app-ids + vendor 10415 in CER — **verify on wire**. |
---

## 3. Blockers to test against open5gs (ordered by severity)

1. **S6a AIA wrong vector placement (critical).** Open5GS wraps RAND/XRES/AUTN/KASME inside
   `Authentication-Info(1490)→E-UTRAN-Vector(1491)`; our `HssService.air` returns them **flat**,
   and `S6aDiameterMapper.toAnswer` hex-encodes them. An Open5GS/real-MME test fails to consume the
   vector. The `Map<Integer,String>` flow has **no grouped AVP carrier** — core blocker for every
   grouped answer (AIA, ULA, MAA, CCA).

2. **Cx MAR not routed by the gateway.** `DiameterGatewaySbb.cx()` (…Sbb.java:121-152) only handles
   SAR(301)/UAR(300)/LIR(302); **MAR(303) falls to `default → null → 5001`**. `CxService.mar` vector
   logic is unreachable on the wire.

3. **S6a ULA missing grouped Subscription-Data.** Open5GS builds Subscription-Data(1400) with
   MSISDN(701), AMBR(1435), APN-Configuration-Profile(1429)…; we emit flat JSON + `ul=..;dl=..` string.
   A real MME/PGW won't get GBR/APN profile.

4. **Gx/Rx answers are flat strings, not grouped Diameter AVPs.** Charging-Rule-Install,
   Default-EPS-Bearer-QoS, Usage-Monitoring, and QoS-Info are single strings; `Map<Integer,String>`
   cannot carry Charging-Rule-Definition(1003)/Flow-Information(1058)/ARP(1014)/IP-Can-Type/RAT-Type.

5. **Server-initiated paths missing** (CLR/IDR to previous-MME, Gx RAR, Cx RTR/PPR, Rx ASR). Our SBBs
   only respond to inbound requests.

6. **Cx success Experimental-Result codes.** Open5GS UAA uses 2002/2003; we use 2001 — S-CSCF
   vendors may branch on FIRST/SUBSEQUENT_REGISTRATION.

7. **CER application-Id/vendor advertisement (verify).** Confirm `ra-diameter` CER advertises vendor
   10415 + app-ids 16777251/16777216/16777238/16777236 so Open5GS sees a usable peer.
---

## 4. Recommended concrete fixes

> All are compatibility/wiring **encodings** — no SIP/IMS state-machine change. Groupings imply
> extending the flat `Map<Integer,String>` contract (nested-map carrier or RA-serializable group
> leaves) before they can go on the wire. None touch operator `configs/` or share secrets.

1. **Extend the Diameter AVP carrier to express groups.** `Map<Integer,String>` (S6aMessage.java,
   CxResult, GxHandler, SendDiameterAnswer) cannot carry `Authentication-Info`, `SIP-Auth-Data-Item`,
   `Charging-Rule-Definition`, or `QoS-Information`. Add a nested carrier
   (`Map<Integer,Object>` with List/Map leaves) at the `ra-diameter` boundary, or defined per-group
   opaque encodings the RA serializes. **Prerequisite for fixes 2–5.**

2. **`HssService.air`**: emit `Authentication-Info(1490)→E-UTRAN-Vector(1491)→{RAND,XRES,AUTN,KASME}`
   and add `Auth-Session-State(277)=1`. (Requires fix 1.)

3. **`HssService.ulr` / `S6aSubscriptionData`**: rebuild Subscription-Data as grouped with MSISDN(701),
   AMBR(1435)→UL/DL, per-APN APN-Configuration(1430); add ULA-Flags(1406) and 2× Supported-Features.

4. **`DiameterGatewaySbb.cx`**: add `case 303 (MAR)` → `cx.mar(impi, impu)`, assembling MAA with
   Public-Identity(601) + SIP-Auth-Data-Item(612) group {Item(613), Scheme(608), Authenticate(609)=RAND‖AUTN,
   Authorization(610), CK(625), IK(626)}. Currently returns 5001 for MAR.

5. **`CxService.sar`**: add Charging-Information(618)→Primary-CCF-Name(620) to SAA; honor
   User-Data-Already-Available. (Requires fix 1.)

6. **`CxService.uar` / gateway**: return app codes FIRST_REGISTRATION(2002)/SUBSEQUENT_REGISTRATION(2003)
   via Experimental-Result instead of 2001 for S-CSCF parity. (Requires fixing the CxResult success override.)

7. **`GxHandler`**: emit grouped Charging-Rule-Definition(1003) {Name, Flow-Information→Flow-Description/Direction,
   Flow-Status, QoS-Information→QCI/ARP} and grouped Default-EPS-Bearer-QoS(1023); drop `;`/`|` inline string
   encoding on the wire. Add Rating-Group only if our rules need per-rule rating (open5gs omits it).

8. **`RxHandler`/`PcrfSbb.rx`**: add IP-Can-Type(1027)=3GPP-EPS and RAT-Type(1032)=EUTRAN to AAA; wire the
   existing buildRar/buildAsr into a real server-initiated send (Gx RAR, Rx ASR).

9. **Server-initiated S6a**: add `HssService.sendClr(previousMme)`/`sendIdr(mme)` producers (mirroring
   `hss_s6a_send_clr` hss-s6a-path.c:1850 / `hss_s6a_send_idr` 2060) so an MME change triggers CLR to the
   old MME with Cancellation-Type(1420). Not needed for basic attach to Open5GS; needed for MME-change parity.

---

## Non-fixes / intentionally out of scope (factual)
- **NOTIFY (NOR)**: Open5GS S6a doesn't implement it; our NOR→NOA superset is fine and not a blocker.
- **PUR/CLR direction**: Open5GS CLR is server→MME; our inbound CLR/CLA ack is a different (still valid S6a)
  direction — keep both, add the server send side.
- No change to Diameter app-ids or command codes — they already match exactly.

## Open Maybes (not proven in this tree)
- Whether `ra-diameter`'s CER advertises vendor 10415 + the four app-ids, and whether `isPeerReady()` (CEA)
  is sufficient for Open5GS to route command codes — verify on the wire during the bakeoff, not from Java files.
**NOTIFY (NOR, 323)** — **NOT implemented** in Open5GS S6a (only AIR, ULR, PUR request handlers — `hss_s6a_init` 2294-2330).