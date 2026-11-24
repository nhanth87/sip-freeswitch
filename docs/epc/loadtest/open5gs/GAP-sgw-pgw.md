# GAP report: our EPC SGW-C/PGW-C vs Open5GS SGW-C/SGW-U

Source read (local `loadtest/ims-tools/open5gs`):
`src/sgwc/s11-handler.c`, `src/sgwc/s11-build.c`, `src/sgwc/s5c-handler.c`,
`src/sgwc/sxa-handler.c`, `src/sgwc/sxa-build.c`, `src/sgwc/pfcp-path.c`,
`src/sgwc/pfcp-sm.c`, `src/sgwc/gtp-path.c`, `src/sgwc/sgwc-sm.c`,
`src/sgwu/sxa-handler.c`, `src/sgwu/gtp-path.c`, `lib/gtp/v2/{path,build,message}.c`,
`lib/gtp/xact.c`.

Our code: `epc/epc-sgwc/.../SgwControl.java`, `epc/epc-pgwc/.../PgwControl.java`,
`epc/epc-host/.../sbb/GtpGatewaySbb.java`, `epc/epc-host/.../EpcBootstrap.java`,
vendor `ra-gtpv2c` / `ra-pfcp` jars.

This is a DISCOVERY report only — no shared code was edited.

---

## 1. Open5GS SGW message inventory

### 1a. S11 (MME ⇄ SGW-C), `sgwc/s11-handler.c`

Open5GS SGW-C implements these **inbound message types** on S11:

| Type | Handler | Notes |
|------|---------|-------|
| `CREATE_SESSION_REQUEST` | `sgwc_s11_handle_create_session_request` | mandatory flow |
| `MODIFY_BEARER_REQUEST` | `sgwc_s11_handle_modify_bearer_request` | path switch |
| `DELETE_SESSION_REQUEST` | `sgwc_s11_handle_delete_session_request` | |
| `RELEASE_ACCESS_BEARERS_REQUEST` | `sgwc_s11_handle_release_access_bearers_request` | |
| `DOWNLINK_DATA_NOTIFICATION_ACK` | `sgwc_s11_handle_downlink_data_notification_ack` | |
| `CREATE_INDIRECT_DATA_FORWARDING_TUNNEL_REQUEST` | `..._handle_...` | HO (X2/S1 indirect tunnels) |
| `DELETE_INDIRECT_DATA_FORWARDING_TUNNEL_REQUEST` | `...` | HO |
| `BEARER_RESOURCE_COMMAND` | `..._handle_bearer_resource_command` | |
| `CREATE_BEARER_RESPONSE` | `..._handle_create_bearer_response` | from MME |
| `UPDATE_BEARER_RESPONSE` | `..._handle_update_bearer_response` | |
| `DELETE_BEARER_RESPONSE` | `..._handle_delete_bearer_response` | |

Outbound S11 responses open5gs builds: Create Session Response (`s11-build.c`,
`sgwc_s11_build_create_session_response`), Modify Bearer Response,
Delete Session Response, Release Access Bearers Response, Downlink Data
Notification (to MME when paging required on the SGW-U), Indirect forwarding
tunnel responses. Echo is handled at the GTP lib layer (below).

**Create Session Request — IEs open5gs REQUIRES / reads (`s11-handler.c` 166-449):**

Mandatory / conditional (enforced with a cause if missing):
- **IMSI** (`req->imsi.presence`, conditional) → `OGS_GTP2_CAUSE_CONDITIONAL_IE_MISSING`
- **Bearer Context → EPS Bearer ID** (`bearer_contexts_to_be_created[i].eps_bearer_id`, mandatory)
- **Bearer Context → Bearer Level QoS** (`bearer_level_qos`, QCI/ARP, mandatory)
- **APN** (`access_point_name`, mandatory, validated by `ogs_fqdn_parse`)
- **Sender F-TEID for Control Plane** (`sender_f_teid_for_control_plane`, mandatory) → MME's S11 TEID
- **PGW S5/S8 Address for Control Plane or PMIP** (`pgw_s5_s8_address_for_control_plane_or_pmip`, conditional) — how open5gs learns the PGW: an **F-TEID embedded in the CSReq by the MME**, NOT a DNS NAPTR lookup in SGW-C. (MME does NAPTR/selection; SGW-C consumes the F-TEID.)
- Optional reads: **ULI** (`user_location_information`), Serving Network, RAT type, UE IP for DL paging, bearer `s4_u_sgsn_f_teid`/`s5_s8_u_sgw_f_teid`, PCO, MSISDN, ME identity.

Note: open5gs **requires** `pgw_s5_s8_address_for_control_plane_or_pmip` presence; it seeds `sess->pgw_s5c_teid` (line 425) and programs the DL FAR toward PGW-U.

**Modify Bearer Request — IEs required (`s11-handler.c` 449-679):**
- Bearer Context → **EPS Bearer ID** (mandatory)
- Bearer Context → **S1-U eNodeB F-TEID** (`s1_u_enodeb_f_teid`, mandatory)
- Optional: ULI. open5gs loops `OGS_BEARER_PER_UE` contexts, each triggers a PFCP **Modify** (`DL_ONLY|ACTIVATE`). Per-bearer context required even for a single default bearer.

**Delete Session Request** (`679-848`): correlates by header S11 TEID; sends S5-C Delete Session + Sxa Session Deletion; responds Delete Session Response.

**Release Access Bearers Request** (`1492-1553`): for **each** session issues PFCP Session Modification `OGS_PFCP_MODIFY_DL_ONLY | OGS_PFCP_MODIFY_DEACTIVATE` (idle teardown of DL), keeps S5 context; async Sxa completion drives RABR Response + S5 Modify Bearer toward PGW.

### 1b. S5-C (SGW-C ⇄ PGW-C), `sgwc/s5c-handler.c` + `sgwc/sxa-handler.c`

- SGW-C sends to PGW-C a **fresh `CREATE_SESSION_REQUEST`** (built inline in `sgwc_sxa_handle_session_establishment_response`, lines 416-443) with:
  - header TEID = `sess->pgw_s5c_teid` (the F-TEID taken from the MME CSReq)
  - **Sender F-TEID for Control Plane** (SGW S5-C TEID)
  - **Bearer Context → S5/S8-U SGW F-TEID** (`OGS_GTP2_F_TEID_S5_S8_SGW_GTP_U`)
  - PGW address IE **removed** (`presence = 0`) — relies on header TEID / known PGW g-node.
- SGW-C **expects back** (`sgwc_s5c_handle_create_session_response`, 90-358):
  - Cause
  - **PGW S5/S8 S2a/S2b F-TEID for control plane** (conditional → `CONDITIONAL_IE_MISSING`)
  - **PDN Address Allocation** (`pdn_address_allocation`, conditional, IPv4..IPv4v6)
  - **Bearer Context → EPS Bearer ID + Cause + S5/S8-U SGW F-TEID** (PGW S5-U TEID; drives DL FAR outer-header creation toward PGW-U)
- Then issues Sxa **Session Modification `UL_ONLY | OUTER_HEADER_REMOVAL | ACTIVATE`** to wire UL eNB↔SGW-U↔PGW-U, and completes the MME's S11 CSReq with `sgwc_s11_build_create_session_response`.

Also S5-C: Modify Bearer Response, Delete Session Response, Create/Update/Delete
Bearer Request (PGW→SGW→MME), Bearer Resource Failure Indication (dedicated-bearer paths, not default attach).

### 1c. Sxa PFCP (SGW-C ⇄ SGW-U) `sgwc/sxa-build.c`, `sgwc/pfcp-path.c`, `sgwc/pfcp-sm.c`

SGW-C drives SGW-U via:
- **Association Setup Request / Response** (`pfcp-sm.c` FSM `will_associate`→`associated`); heartbeats in `associated`.
- **Heartbeat Request/Response** (`node_timeout`).
- **Session Establishment Request** (`sgwc_pfcp_send_session_establishment_request`): **Node ID + CP F-SEID + Create PDR/FAR/URR/QER/BAR**. open5gs builds **PDR** (sent F-TEID, SDF filter), **FAR** (apply action FORW + outer header creation toward peer), **QER** (gate/rate), **URR** (usage reporting), and **BAR** — not a single "PDR" blob.
- **Session Modification Request** (`sxa-build.c` 118-343): ordered `update_pdr`/`update_far`/`remove_pdr`/`remove_far`/activate/deactivate/end-marker/outer-header-removal.
- **Session Deletion Request**.
- Handles **Session Report Request** from SGW-U (downlink data → paging) and replies Session Report Response.
- `pfcp-sm.c`: node FSM `initial/final/will_associate/associated/exception` + `pfcp_restoration` on recovery-timestamp change.

### 1d. GTPv2-C Echo / version / recovery (`sgwc/gtp-path.c` + `lib/gtp/v2/*`)

- GTP-C served on one UDP socket pair v4/v6 (`sgwc_gtp_open`).
- Echo handled at **GTP lib layer**, not SGW-C: `ogs_gtp2_handle_echo_req` (lib/gtp/v2/path.c) validates **version=1 + PT/GTP flags**, echoes matching sequence + **Recovery IE**. `ogs_gtp2_build_echo_request(type, recovery, features)` sends Echo Request with **Recovery IE + Sending Node Features IE** (build.c 22-39).
- Recovery IE (`OGS_GTP2_RECOVERY_TYPE`) is the restart counter: on value change open5gs runs `pfcp_restoration` / re-associates.
- Version mismatch → `VERSION_NOT_SUPPORTED_INDICATION`; malformed header → drop.
- **Key semantics our ra-gtpv2c must send to open5gs:** Echo carrying a **Recovery IE**, version=1 + PT bit, sequence matching, optional Sending Node Features. `GtpEchoCommand`/`GtpEchoEvent` exist in the jar (S11 echo covered if RA auto-responds); S5/PFCP echo is separate.

### 1e. GTP-U / SGW-U (`sgwu/gtp-path.c`, `sgwu/sxa-handler.c`)

- **SGW-U runs GTP-U entirely in userspace** (`sgwu_gtp_open`, `_gtpv1_u_recv_cb`, drain budget `SGWU_SK_DRAIN_BUDGET=32`). Parses GTP-U header, answers **GTP-U Echo Request** inline, dispatches each packet to a **PDR**; on DL-with-no-UL-route raises a **downlink data report** → `sgwu_pfcp_send_session_report_request` (PFCP Session Report to SGW-C). END_MARKER just passed through.
- `sgwu_sxa_handle_session_establishment_request`: creates PDR/FAR/QER/BAR, sets up FAR GTP-U g-nodes, installs **TEID hashes** for fast GTP-U lookup, sends buffered DL packets. Handles Session Mod / Deletion / Report Response.
- No TUN/GTP kernel module: **userspace UPF-style SGW-U**, unlike our declared "kernel GTP-U out of band" stance.

---

## 2. Our coverage table

| Flow | open5gs IEs/messages | our handler | Verdict | Note |
|------|----------------------|-------------|---------|------|
| S11 CSReq | IMSI, Bearer Ctx{EBID,QoS}, APN, Sender F-TEID(S11), **PGW S5/S8 F-TEID**, ULI | `GtpGatewaySbb.createSession`→`SgwControl.createSession` reads `imsiDigits`,`apn`,`senderFteid`; **does NOT read `pgw_s5_s8_address_for_control_plane_or_pmip`** | **MISSING (partial)** | PGW co-located in-process; wire PGW selection + ULI/QoS parsing absent. |
| S11 CSResp | Cause, SGW S11 F-TEID, Bearer Ctx{EBID,Cause,S1-U S-GW,S5/S8-U SGW} | `createSessionResponse` sends Cause + SGW S11 FTEID + PAA + Bearer Ctx(S1U) | OK (subset) | **Missing PGW S5/S8-U SGW F-TEID** in Bearer Ctx (only S1-U put) and no SGW S5-C/S5-U TEIDs. Acceptable only if PGW in-process and MME doesn't parse it. open5gs returns full set. |
| S11 MBReq | BEARER Ctx{EBID, **S1-U eNB F-TEID**} | `modifyBearer` reads `s1uFromBearerContext`, `SgwControl.modifyAccessByTeid`→PFCP reprogram | **OK (subset)** | Single default bearer only; open5gs loops `OGS_BEARER_PER_UE`, each per-bearer Modify. No PGW-side S5 Modify. |
| S11 DeleteSession | correlate header TEID → S5 del + Sxa del | `deleteSession` calls `pgw.deleteSession` (in-process) + responds | OK (subset) | SGW-C should also issue Sxa Session Deletion; `SgwControl` has no delete (only PGW in-process path). |
| S11 RABRelease | per-session PFCP Modify DEACTIVATE (DL only) | `SgwControl.releaseAccessByTeid` sends `PfcpProgramSession` with **empty rule list** | **MISSING (semantics)** | Empty rule list ≠ open5gs `MODIFY_DL_ONLY|DEACTIVATE` DL FAR. Wrong Sxa wire shape. |
| S11 DDN ack | ack 177 | `downlinkDataNotification` sends DDN-Ack on a **received** DDN (unusual on SGW) | OK/compat | We never originate DDN toward MME on DL page (no SGW-U downlink report handling). |
| S11 Indirect fwd tunnels | Create/Delete Indirect Data Forwarding Tunnel | **none** | MISSING | HO tunnels unimplemented. |
| S11 Create/Update/Delete Bearer Response, Bearer Resource Cmd | dedicated-bearer procedures | **none** | MISSING | default-bearer attach unaffected; PGW-initiated bearers absent. |
| S5-C CSReq→PGW | fresh CSReq to PGW (header TEID=PGW F-TEID; Sender FTEID S5-C; Bearer Ctx S5/S8-U SGW) | **no S5-C wire** — `SgwControl` calls `PgwControl.createSession` in-process | **MISSING (architectural)** | Real S5-C GTPv2 transaction required to attach against an external PGW-C or to be tested against open5gs SGW-C. |
| S5-C CSResp from PGW | Cause, PGW S5-C F-TEID, PAA, Bearer Ctx{EBID,S5/S8-U PGW} | `pgwCreateSessionResponse` builds exactly these from in-process PGW | OK (subset) | Matches open5gs SGW-C expectations **if** emitted; only wired for PGW-side bootstrap, not as S5-C response. |
| Sxa Session Estab | Node ID + CP F-SEID + Create **PDR/FAR/URR/QER/BAR** | `PfcpProgramSession` builds a **PDR-generated rule** (`new PfcpRule(se,"pdr-ul",…)`) | **MISSING (shell)** | `ra-pfcp` placeholder: single PDR wrapper, no FAR apply-action+outer-header, no QER gate/rate, no URR, no BAR. Cannot interop with open5gs SGW-U. |
| Sxa Assoc Setup | Association Setup Req/Rsp + Heartbeat | `PfcpAssociateCommand` exists in jar but **SBB/SgwControl never send it**; only `PfcpProgramSession`. SBB reads only `PfcpMessageEvent`. | **MISSING (runtime)** | No association FSM, no heartbeat, no recovery/restoration handling. |
| Sxa Session Mod | granular update_pdr/update_far/remove_*/activate/deactivate/end-marker | `modifyAccess`/`releaseAccess` full-reprogram via `PfcpProgramSession` | **MISSING (shape)** | Full-reprogram is not the Sxa Modification contract. |
| Sxa Session Report | SGW-U→SGW-C downlink data report (paging) | **none** (`onPfcp` only stores SEID) | MISSING | Needed for paging/DDN toward MME when kernel-UP out-of-band doesn't report. |
| GTP-U (S1-U/S5-U) | open5gs SGW-U **userspace** GTP-U + per-PDR dispatch + GTP-U echo | `epc-up-linux` **kernel GTP-U out of band** | **DIVERGENT** | Not interoperable with open5gs SGW-U at Sxa programming level today. |
| GTPv2-C Echo/version/recovery | Echo Req/Rsp with **Recovery IE** + version=1/PT; restart→re-associate | `ra-gtpv2c` exposes `GtpEchoCommand`/`GtpEchoEvent`; `GtpGatewaySbb.onGtp` **does not** handle echo/recovery | **MISSING (SBB)** | Verify RA auto-responds Echo w/ Recovery; likely need SBB handling + recovery→PFCP re-association. |

---

## 3. Blockers to attach against open5gs

1. **No S5-C wire (greatest architectural blocker).** `SgwControl` talks to
   `PgwControl` in-process; no S5-C Create Session exchange, so we cannot point
   our SGW-C at an external PGW-C (open5gs **pgw**) nor be tested against
   open5gs SGW-C using the MME CSReq flow. Attach is blocked without this.
2. **Sxa PFCP programming is a shell.** `PfcpProgramSession`/`PfcpRule` do not
   encode open5gs PDR/FAR/QER/URR/BAR + Node ID + CP F-SEID + Assoc + Heartbeat
   + recovery semantics. open5gs SGW-U would reject or mis-program; Session
   Establishment/Modification/Deletion shapes don't match.
3. **PGW selection / DNS NAPTR not implemented on SGW-C.** open5gs SGW-C reads
   the `PGW S5/S8 Address for Control Plane or PMIP` F-TEID from the MME CSReq
   (MME did NAPTR). Our `createSession` ignores that IE — against an open5gs MME
   we'd need to consume it and route S5 accordingly.
4. **No association/heartbeat/recovery FSM on `pfcp-ra`.** Though
   `PfcpAssociateCommand`/events exist, the SBB never drives association nor
   reacts to recovery-timestamp changes (no `pfcp_restoration` equivalent).
5. **Release Access Bearers sends `List.of()` (empty rule set)** instead of a
   Pfcp Modification with DL FAR deactivate — wrong Sxa payload on the idle path.
6. **GTP-U userspace vs kernel.** open5gs SGW-U is userspace GTP-U with per-PDR
   TEID hashing + DL-data reporting; our kernel-GTP-U-out-of-band stance cannot
   interop with open5gs SGW-U as-is (and gives no downlink-data report to drive
   DDN/paging).

---

## 4. Recommended concrete fixes (safe, additive — NOT applied)

1. **Add `Gtpv2Ies.pgwS5cFteid(msg)`** decoder for IE
   `pgw_s5_s8_address_for_control_plane_or_pmip` and read it in
   `GtpGatewaySbb.createSession` → pass to `SgwControl.createSession`; store in
   the checkpoint. Safe additive.
2. **Add `SgwControl.deleteSession(imsi,apn)`** that issues an Sxa deletion
   program (needs a `PfcpDeleteSession` command on `ra-pfcp`), then PGW in-process
   teardown, and have `GtpGatewaySbb.deleteSession` call it.
3. **Fix `SgwControl.releaseAccessByTeid`** to send a **Deactivate-DL FAR**
   program (command variant carrying `deactivate-dl` + keep-UP) rather than
   `List.of()`.
4. **Wire `PfcpAssociateCommand` + `GtpEchoCommand`** in `EpcBootstrap`/`SgwControl`
   at boot: Association Setup / Heartbeat on the SGW-U peer; handle
   `PfcpAssociationEvent`/`PfcpHeartbeatEvent`/`GtpEchoEvent` in the SBB to drive
   recovery re-association.
5. **Add SBB handling for `GtpEchoEvent`** (respond Echo matching sequence +
   Recovery IE; on Recovery change trigger PFCP re-association) — verify the RA
   doesn't already; if it does, document rather than double.
6. **Expand `CreateSessionResponse`** to include **S5/S8-U SGW F-TEID** in the
   returned Bearer Context and PGW S5-C F-TEID (parity with
   `sgwc_s11_build_create_session_response`), guarded so co-located mode is
   unaffected.
7. **Real S5-C wire (larger, later):** introduce `S5cGatewaySbb`/command on
   `ra-gtpv2c` to send S5-C Create Session to an external PGW-C and correlate the
   response back to the S11 transaction (mirror open5gs `s5c-handler.c` +
   `sxa-handler.c` association). Required to attach against open5gs.

---

## Notes / non-goals of this report

- No shared code modified (DISCOVERY task). All recommendations are additive.
- Dedicated-bearer procedures (Create/Update/Delete Bearer *, Bearer Resource
  Cmd, Indirect Data Forwarding Tunnels) are out of scope for default-bearer
  attach but are the largest S11 surface open5gs implements.
- "verdict OK" for CSResp/MBResp is relative to our in-process model, not
  open5gs parity.