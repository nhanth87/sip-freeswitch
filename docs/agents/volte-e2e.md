# VoLTE end-to-end demo — UE attach → EPC → IMS → FreeSWITCH (2026-10-29)

Goal: one call that crosses every node on the wire, each hop proven by pcap, recorded as a
video with English subtitles and real audio (UE speech up, FreeSWITCH tone down).

```text
 srsUE ──ZMQ── srsENB ──S1AP── MME (open5gs) ──S6a── epc-hss (AuC, Postgres)
   │                             │
   │ GTP-U (S1-U)                └──S11── epc-sgwc ──S5── epc-pgwc ──Gx── epc-pcrf
   ▼                                        │ Sxa        │ Sxb            ▲ Rx
 SGW-U ────────── S5-U ─────────────────── PGW-U ── SGi ──┐               │
                                                          ▼               │
                       SIPp (in UE netns) ──Gm── P-CSCF ──┘───────────────┘
                                                   │
                                     I-CSCF ──Cx (UAR/LIR)── epc-hss
                                                   │
                                     S-CSCF ──Cx (MAR/SAR, AKA)── epc-hss
                                                   │
                                              FreeSWITCH (sip:1000, tone) ◄── RTP over GTP-U
```

HLR (MAP SendAuthenticationInfo over SS7) is not on the VoLTE path; it gets its own round (G5).

## Gates (each = steps + pcap under `loadtest/pcaps/volte/`)

| Gate | Proves | Status |
|------|--------|--------|
| G0 | images rebuilt from the pushed code; mvn lab stopped | **done** |
| G1 | S6a CER/CEA MME↔epc-hss, S11 Echo MME↔epc-sgwc | **S6a done** (`volte-g1.pcap`: CER/CEA 2001, DWR/DWA every 30 s, DPR/DPA on MME restart); S11 only appears with Create Session (G2) |
| G2 | RAN attach: S1AP Attach, S6a AIR/ULR, S11 Create Session, Sxa/Sxb PFCP, UE IP on `internet` + `ims`, PCO carries the P-CSCF address | — |
| G3 | IMS over the bearer: REGISTER 401/200 (AKA vector from epc-hss AuC via Cx MAR, UAR/SAR), INVITE → FS 200, RTP both ways through GTP-U | — |
| G4 | Policy: P-CSCF Rx AAR → PCRF → Gx RAR → PGW-C → Create Bearer (QCI 1) → MME/eNB; STR on BYE | — |
| G5 | HLR: MAP SAI → quintuplets (separate round) | — |
| G6 | video (browser tab) + call audio + English subtitles; per-hop pcap summary | — |

## Decisions

- UE/eNB: srsRAN 4G over ZMQ (no RF). UE IP lives in the srsUE container netns; SIPp runs in
  that netns (`docker run --net container:srsue`), so SIP and RTP really ride the bearer.
- User plane: our SGW-C/PGW-C drive the UP over PFCP; the UP choice is recorded in G2.
- Media: SIPp `rtp_stream` (plain UDP socket) — `play_pcap_audio` needs a raw socket (root).

## Findings while bringing the chain up (2026-10-29)

1. **ra-diameter pinned every wildcard link to 127.0.0.1** (`CorsacDiameterTransport.listenHost`):
   a server listened on loopback only, a client bound its source to loopback — no Diameter
   link ever crossed hosts/containers. Fixed: wildcard server → `0.0.0.0`, wildcard client →
   route source towards the peer (`CorsacLocalBindAddressTest`).
2. **EPC status showed S6a down while it was up**: `S6aPeerWatch` was a bind-time snapshot and
   Corsac answers CER/DWR inside its stack. Fixed: the watch tracks the live Diameter port.
3. **Capture**: the `pcap` sidecar only sees its own eth0 on a Docker bridge. Capture on the host
   bridge (`br-<sf-net id>`) from a `--net host` tcpdump container; stop it with SIGINT.
4. **Images log nothing from micro-jainslee RAs** without a log4j2 config → `docker-compose.volte.yml`
   mounts `deploy/volte/log4j2.xml`.
5. **Done — several Diameter peers per RA.** ra-diameter now opens one Corsac link per configured
   peer (`EPC_DIAMETER_PEERS` / `DiameterRaConfig.parsePeers`): answers leave on the link their
   request came in on, requests go by Destination-Host → application → realm; Rx registered.
   Wire (`volte-g1-cx.pcap`): epc-hss CER → MME / I-CSCF / S-CSCF, each CEA 2001
   (`CorsacMultiPeerTest` opens two real links on loopback). A server-role peer resolves its
   peer host at start — start it after that host exists (I-CSCF before epc-hss failed with
   UnknownHostException).
6. **Open — user plane / PFCP is the critical path.** `sgwu` / `pgwu` are alpine placeholders, and
   per `epc/loadtest/open5gs/GAP-sgw-pgw.md` our SGW-C/PGW-C PFCP is a shell: no Association
   Setup / Heartbeat, no real PDR/FAR/QER encoding, Session Modification = full reprogram. A
   standard UP (open5gs `sgwud` / `upfd`, both in `local/open5gs-mme:epc`) needs: Association,
   Session Establishment with PDR/FAR (Sxa + Sxb), Modification on Modify Bearer (eNB F-TEID),
   Deletion. S5-C is in-process only → run SGW-C + PGW-C in one JVM for attach.
8. **Open — dedicated bearer (G4)**: Create/Update/Delete Bearer on S11/S5 are missing, so the
   Rx → Gx RAR → QCI 1 bearer leg cannot complete until they exist.
7. **Open — Rx**: Elisa's P-CSCF has no Rx client (AAR/STR) yet.

## Core chain demo — done (2026-10-29)

Split IMS core, one container per role, fixed addresses (`docker-compose.volte.yml`):
UE (SIPp, host `172.20.0.1`) → P-CSCF `172.20.10.20` → I-CSCF `.21` → S-CSCF `.22` → FreeSWITCH `.30`;
HSS/AuC = epc-hss `.10` (Postgres, MILENAGE, TS 33.102 Annex C SQN) over Cx, three Diameter links on one RA.

Run (lab up with `--profile epc --profile services` + this overlay):

```bash
sipp -sf loadtest/sipp/uac_register_aka.xml -inf loadtest/sipp/users-aka.csv 172.20.10.20:5060 -i 172.20.0.1 -p 16120 -m 1
sipp -sf loadtest/sipp/uac_volte_call.xml   -inf loadtest/sipp/users-aka.csv -s 1000 172.20.10.20:5060 \
     -i 172.20.0.1 -p 16120 -mi 172.20.0.1 -mp 16200 -m 1
```

Subscriber `akasipp` (IMSI 001010000000009) and the PSI `sip:1000@ims.lab` (MRF) live in epc-hss;
the same line is mirrored in Elisa (`mvno-a`, MSISDN 251911000009) for the MVNO console / CDR.

| Proof | Where |
|-------|-------|
| REGISTER 401/200: UAR→UAA (Experimental-Result 2001/2002), MAR→MAA (AKA vector accepted by SIPp's USIM), SAR→SAA | `loadtest/pcaps/volte/core-call6.pcap` |
| INVITE sip:1000: P → I (LIR → LIA 2001) → S → FreeSWITCH 200, ACK / BYE through every hop | same |
| RTP: uplink speech 710 pkts, downlink tone 711 pkts, 0 lost (G.711 µ-law) | same; extracted `core-call6-rtp-*.wav` |
| Per-hop ladder from the capture | `core-call6-callflow.html` (`loadtest/sipp/media/make-ladder.py`) |
| MVNO console ADMIN → GD → USER, the call's CDR with tenant + MSISDN | `volte-core-demo.mp4` (+ `volte-core-demo.en.srt`), call capture `core-call-video.pcap` |

Fixes this needed (all with tests): ra-diameter — multi-peer links, wildcard bind, Corsac listener fan-out,
command definitions on interfaces (every inbound message was dropped), all AVPs to the event, Result-Code
optional; Corsac fork — `getIsError()` on Experimental-Result-only answers (local install, not pushed);
core — MISSING_ENTITY delivery never on the router thread (ASYNC_COMMIT); epc — Cx Experimental-Result per
TS 29.229, no Server-Name in MAA, Cx with the AuC secrets, AMF = 2 octets from the Postgres secret store,
configurable S-CSCF (`EPC_HSS_SCSCF_URI`), live S6a status, `EPC_DIAMETER_PEERS`; Elisa — typed 3GPP Cx
requests, UAA/LIA success codes, a split S-CSCF does not send UAR.

Open (seen during the run): MISSING_ENTITY routing in Elisa (entities leave the pool while still
routed — now harmless, still to root-cause); a BYE ping-pong between P- and I-CSCF after a failed
REGISTER; S-CSCF answers 200 to REGISTER ~4 s after SAA; MVNO console forms do not submit from the
automation extension (POST works — check in a normal browser); Corsac re-encode length header mismatch
(handled by `framed()`).

## Live demo video — services, SIPp, Wireshark (2026-11-03)

`loadtest/pcaps/volte/volte-live-demo.mp4` (151 s, English subtitles burned in + `volte-live-demo.en.srt`),
call capture `volte-demo-live.pcap`. Recorded off-screen on a Hyprland headless output
(`hyprctl output create headless`, `wf-recorder -o <output>`), scripts in `loadtest/sipp/demo/`:

1. **Terminal** (tmux in kitty): `services.py` (every node: state / health / CPU / MEM, and the Diameter
   links really ESTABLISHED on epc-hss from its `/proc/net/tcp`), `tapfmt.py` (tshark on the sf-net bridge,
   every SIP / Cx message and RTP milestones with node names), the UE pane typing the SIPp REGISTER and call
   commands, then `tshark -z rtp,streams` on the capture (706 + 710 packets, 0 % loss). The call's own RTP
   (speech up / tone down) is the audio track, aligned on the INVITE.
2. **Wireshark** on the same pcap (`wireshark-scene.py`, private `WIRESHARK_CONFIG_DIR` with a `hosts` file
   for node names and custom columns): signalling with Cx command / result / identities; the Cx MAA AKA
   vector (scheme, SIP-Authenticate = RAND‖AUTN, XRES, CK); REGISTER nonce / AKAv1-MD5 / response;
   INVITE / 200 SDP per hop; RTP SSRC / seq / timestamp both ways.

The scripts carry absolute lab paths (scratchpad, bridge name) — adjust before reuse.
