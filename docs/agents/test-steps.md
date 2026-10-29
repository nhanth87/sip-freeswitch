# Test steps — Elisa / EPC / micro-jainslee (2026-10-28)

Every round: **written steps + pcap as evidence**. Numbers without a pcap are not a result.
Unit rounds cite the test class; wire rounds keep the pcap under `loadtest/pcaps/` (large captures:
header-only `-s 256`, trimmed sample committed).

## 0. Capture helper (no sudo)

```bash
CAP=$PWD/loadtest/pcaps
docker run -d --name sf-cap --net host --cap-add NET_ADMIN --cap-add NET_RAW \
  -v $CAP:/cap nicolaka/netshoot tcpdump -i lo -s 0 -w /cap/<round>.pcap \
  "udp port 5060 or udp port 5062 or tcp port 3868 or sctp"
# … run the round …
docker stop sf-cap && docker rm sf-cap
tshark -r $CAP/<round>.pcap -Y sip -T fields -e sip.Method -e sip.Status-Code | sort | uniq -c
```

## 1. Runtime (micro-jainslee) — unit

| # | Steps | Expected |
|---|-------|----------|
| R1 | `cd jain-slee/jain-slee/jainslee-core && mvn -o test -Dtest=ActivityEndRootRemovalTest` | 8/8 (SYNC + ASYNC): root removed with reason `ATTACHMENT_COUNT_ZERO` after its handler saw `ActivityEndedEvent`; IES entity lives while another activity is attached; explicit `aci.detach` → removal after commit; child removed before parent (§6.8) |
| R2 | `mvn -o test -Dtest=SbbFiredEventOverflowTest` | 500 SBB-ended activities on an 8-slot ring in SYNC finish < 10 s (fix off → hangs, verified), no ActivityEnded overtakes its cause |
| R3 | `mvn -o test -Dtest=RegistrationOrderTest` | O(1) registration order, removal-safe iteration |
| R4 | `mvn -o test` (whole core) | 505/505 |
| R5 | `vendor-ras/ra-sip-servlet: mvn -o test -Dtest=SipRaDialogLifecycleTest` | INVITE retransmission fires once; BYE retransmitted after dialog end absorbed; retransmitted 2xx to our INVITE fires once; far 200-BYE keeps dialog while near BYE unanswered. (`byeKeepsDialogUntilFinalResponseThenEnds` is pre-existing red: written before HA sticky routing) |
| R5b | `vendor-ras/ra-sip-servlet: mvn -o test -Dtest='ClientTransactionsTest,SipEndToEndTest'` | RFC 3261 §17.1 UDP client transactions: INVITE Timer A doubles until any response, Timer B (64·T1) → local 408 to the SBB (§8.1.3.1), none after a provisional; non-INVITE Timer E capped at T2, Timer F; ACK / TCP untracked; match = branch + CSeq method (CANCEL ≠ INVITE). New INVITE answered `100 Trying` by the RA (§17.2.1) before the SBB's response |
| R6 | `vendor-ras/ra-jss7: mvn -o test -Dtest=MapMobilityOutboundTest` | SAI quintuplets / triplets encoded on the request dialog and closed; `MapReturnError(1)` = unknownSubscriber |

## 2. AuC / HSS / HLR / PCRF — unit (3GPP anchored)

| # | Steps | Expected |
|---|-------|----------|
| A1 | `epc/epc-crypto: mvn -o test -Dtest='MilenageTs35208Test,UmtsVectorTest'` | TS 35.208 set 1; AUTN = (SQN⊕AK)‖AMF‖MAC-A; AUTS round-trip, bad MAC-S rejected; c2/c3 triplet |
| A2 | `mvn -o test -Dtest=SqnAnnexCTest` | TS 33.102 Annex C: SEQ+1/cyclic IND; out-of-order vectors over 4 nodes accepted by the C.2.2 USIM model; replay rejected; resync from AUTS recovers |
| A3 | `epc/epc-hss: mvn -o test` | 133/133 incl. AIR SQN Annex C, Postgres `UPDATE … RETURNING` with `Sqn.nextSql`, resync |
| A4 | `mvn -o test -Dtest='HlrAuthServiceTest,HlrSbbTest'` | MAP SAI: ≤5 fresh quintuplets with distinct SQN, MAP v2 → triplets, unknownSubscriber, resync / bad MAC-S → unexpectedDataValue |
| A5 | `epc/epc-pcrf: mvn -o test -Dtest=PcrfRuleWriteRarTest` | rule write → durable store → cache invalidated → Gx RAR (cmd 258) to each live session on the APN only |
| A6 | `elisa: mvn -o test -Dtest='AkaMd5Rfc3310Test,Ts24229RegisterFlowTest'` | UE-side check (independent `UeAka`): MAC-A ok, SQN recovered, nonce = base64(RAND‖AUTN), RES-in-clear rejected, digest with XRES password accepted, `auts` → MAR resync → fresh 401 |

## 3. Wire rounds (pcap mandatory)

### W1 — IMS AKA REGISTER through Elisa (RFC 3310)

Pre: mvn lab up (`docker compose -f freeswitch/docker-compose.yml up -d`, `./run.sh`), subscriber with
Ki/OPc provisioned (admin `/admin/profiles`).

SIPp's AKA keyword `memcpy`s `aka_K` / `aka_OP` / `aka_AMF` as **raw string bytes** (not hex) and does
not expand `[fieldN]` inside `[authentication …]`. So the wire round uses a dedicated subscriber whose keys
are ASCII strings (3GPP hex test sets stay in the unit tests, A1/A6):

```bash
K=$(printf sippakalabkey001 | xxd -p); OP=$(printf sippakalabop0001 | xxd -p)
E=$(printf sippakalabop0001 | openssl enc -aes-128-ecb -K $K -nopad | xxd -p)
OPC=$(python3 -c "print('%032x'%(int('$E',16)^int('$OP',16)))")     # TS 35.206: OPc = E_K(OP) ⊕ OP
curl -b admin.cookies -X POST localhost:8088/admin/subscribers \
  --data "sipUser=akasipp&msisdn=251911000009&ki=$K&opc=$OPC&amf=3830"   # AMF = "80"; no sipPassword → AKA, not MD5
```

1. Start capture (§0) as `w1-aka-register`.
2. `sipp -sf loadtest/sipp/uac_register_aka.xml -inf loadtest/sipp/users-aka.csv 127.0.0.1:5060 -p 16001 -m 1`
3. Stop capture.

Expected on wire: `REGISTER` → `401` with `WWW-Authenticate: Digest … nonce="<base64 32 octets>", algorithm=AKAv1-MD5`;
second `REGISTER` with RFC 3310 digest → `200`. `tshark -Y 'sip.Status-Code==401' -V | grep nonce` shows base64, not hex.

**Result 2026-10-29** — `loadtest/pcaps/w1-aka-register-20261029.pcap`: REGISTER → 401
`nonce="Dzfz2RFw/zNj3UsZ1zgM0ZV87KXUATgwdl3jXbocEdU="` (32 octets), `algorithm=AKAv1-MD5` → REGISTER
`algorithm=AKAv1-MD5, response=…` → **200**. SIPp's independent USIM (3GPP reference MILENAGE) accepted
MAC-A, so the AuC interoperates; it is not just self-consistent.

### W2 — PCRF policy push (Gx RAR)

1. EPC host with `epc.role.pcrf=true`, Gx peer (PGW-C/PCEF) connected; capture `tcp port 3868`.
2. `curl -X PUT localhost:8099/epc/pcrf/rules/internet/video-gbr -d qci=2 -d arp=5 -d mbrDl=8000000 -d gbrDl=4000000`
3. Expected: JSON `rarSessions` = live sessions on `internet`; `tshark -Y 'diameter.cmd.code==258 && diameter.flags.request==1'` count equals it.

### W3 — HLR SendAuthenticationInfo (MAP over SS7)

Open: needs `ra-jss7` + SS7 stack wired on `epc-host` and a VLR/SGSN simulator (jSS7 lab pair from
ussdgw/OTA). Expected: `gsm_map.opr.code == 56` (SAI) begin → end with 1–5 quintuplets.

### W4 — MVNO chain functional rounds

UE (SIPp) → P-CSCF → I-CSCF (Cx UAR/LIR → HSS) → S-CSCF (Cx MAR/SAR, ISC) → FreeSWITCH. One JVM per role.
Rounds: REGISTER AKA 401/200; INVITE MRF 1000 200/ACK/BYE; A→B between registered UEs; unregistered
known callee 480; disabled tenant 403; barred (MMTel) 403. Per round: capture, SIPp stats, tshark per-hop
method/status counts.

**Result 2026-10-29 (combined node)** — `loadtest/pcaps/w1-w4-functional-20261029.pcap`: alice → 1000
INVITE 100/200, ACK, BYE 200; bob (tenant `mvno-off` disabled) → **403**; alice → carol (not registered) → **480**.

### W5 — Performance: 100 000 calls

1. Warm-up 300 calls; `fs_cli -x 'hupall NORMAL_CLEARING'`; note `cat /proc/loadavg`.
2. Capture header-only: `tcpdump -s 256 … -w /cap/w5-100k.pcap`.
3. `sipp -sf loadtest/sipp/uac_invite_bye.xml -s 1000 127.0.0.1:5060 -r <cps> -m 100000 -l <2*cps> -trace_stat -stf …`
4. Report: successful/failed, RT p50/p99, retransmissions, max sustained CPS, FS channels left, Elisa
   entity count before/after (`jcmd GC.class_histogram | grep SimpleSbbLocalObject`), tshark INVITE/200/BYE per hop.

**Result 2026-10-29 (combined node, 4-core laptop, loadavg 4–9 from other apps)** — `uac_invite_from.xml`
alice → 1000 (INVITE, 200, ACK, 200 ms, BYE), `-r 120 -m 100000 -l 3000`:

| Metric | Value |
|---|---|
| Successful / failed | **100 000 / 0** |
| Retransmissions (SIPp) | **0** |
| Rate / elapsed | 119.97 CPS / 13 min 53 s |
| Mean call length | 227 ms (200 ms pause + BYE round-trip) |
| FS channels left | 0 |
| Elisa entities before → +40 s after | SimpleSbbLocalObject 6 → 7, Dialog 0 → 1 (no leak) |

Per hop (full header-only capture 338 MB, 1.3 M packets, kept outside the repo; first 6 000 packets in
`loadtest/pcaps/w5-100k-sample-20261029.pcap`, SIPp stats `w5-100k-sipp-stat-20261029.csv`):
SIPp→Elisa INVITE/ACK/BYE 100 000 each; Elisa→FS INVITE 100 001, ACK 100 000, BYE 100 000; FS→Elisa
200 = 200 001 (INVITE + BYE). Elisa→SIPp 100 = 200 000 (RA `100 Trying` + the relayed FS 100, redundant
but harmless).

Ceiling on this host: 150 CPS already queues (1 132 retransmissions / 3 750 calls), 200 CPS fails. Elisa
spends ~1 core per 100 CPS (ESL `CHANNEL_*` plain events + CallSession + JIT/ZGC), FS ~0.8 core. A
dedicated box is needed for a higher figure.
