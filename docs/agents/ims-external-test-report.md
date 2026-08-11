# IMS external test suites vs Elisa (lab report)

**Date:** 2026-08-10  
**Workspace:** `worktrees/voice-service/sip-freeswitch`  
**Scope:** Lab only. **Not** Digicom production prove. Elisa = CSCF (+ HSS-lite), **not** UPF/AMF/SMF, **not** Gx/PFCP owner.

Canonical tool dir: [`loadtest/ims-tools/`](../../loadtest/ims-tools/)  
On-change smoke: [`./tools/ims-smoke.sh`](../../tools/ims-smoke.sh)  
Report companion scenarios: [`loadtest/ims-tools/sipp-scenarios/`](../../loadtest/ims-tools/sipp-scenarios/)

---

## Verdict (usable now)

| Suite | Usable against Elisa CSCF today? |
|-------|----------------------------------|
| **SIPp** (`loadtest/ims-tools/sipp` + `sipp-scenarios/`) | **Yes — primary.** REGISTER challenge + INVITE CPS harness |
| `docker_open5gs` **`4g-external-ims-deploy.yaml`** | **Yes for 5GC/EPC + P-CSCF pointer.** Lab run 2026-08-10: SMF `p-cscf → Elisa`, Diameter CER/DWR, Gm REGISTER→403 on wire. **Not** full VoLTE E2E (no UE/RAN; no 401/200). See [`open5gs-elisa-test.md`](open5gs-elisa-test.md) |
| `docker_open5gs` Kamailio/OpenSIPS IMS | Reference / alternate CSCF — **not** used when Elisa is P-CSCF |
| Open5GS `tests/volte` / `vonr` | **No** — Diameter/QoS unit tests, not Gm UAC |
| free5gc | **No** — 5GC only |
| Clearwater live-test / Quaff | Needs Clearwater; Quaff can be re-aimed later |
| pyHSS | Cx peer optional; not a Gm tester |

**Usable now:** vendored **SIPp** binary + Elisa `uac_register_challenge.xml` / existing `loadtest/sipp/uac_invite_bye.xml`, always with **PCAP probe**.  
**Open5GS×Elisa lab detail:** [`open5gs-elisa-test.md`](open5gs-elisa-test.md) · PCAPs `loadtest/pcaps/open5gs-elisa/`.

---

## Inventory (cloned under `loadtest/ims-tools/`)

| Path | URL | Purpose | Compile result |
|------|-----|---------|----------------|
| `sipp/` | https://github.com/SIPp/sipp | SIP UAC/UAS load + AKA auth | **OK** — `sipp/build/sipp` (needs `CMAKE_ROOT=/usr/share/cmake` + clean `PATH=/usr/bin:/bin`) |
| `sipp-scenarios/` | local + SIPp examples | Elisa Gm REGISTER/INVITE XML | N/A |
| `docker_open5gs/` | https://github.com/herlesupreeth/docker_open5gs | Open5GS + optional Kamailio/OpenSIPS IMS + PyHSS | **Images pulled**; `4g-external-ims` lab-run vs Elisa — see [`open5gs-elisa-test.md`](open5gs-elisa-test.md) |
| `open5gs/` | https://github.com/open5gs/open5gs | 5GC/EPC | Not built; VoLTE docs point to Kamailio IMS |
| `free5gc/` | https://github.com/free5gc/free5gc | 5GC | Not built; no IMS CSCF |
| `clearwater-live-test/` | https://github.com/Metaswitch/clearwater-live-test | Ruby Quaff E2E | Ruby missing on host; ships bundled `sipp` binary |
| `quaff/` | https://github.com/rkday/quaff | Ruby SIP DSL | Needs Ruby/gem |
| `pyhss/` | https://github.com/nickvsnetworking/pyhss | Diameter Cx HSS | Python sources present; not started |

Compat symlink: `loadtest/third_party` → `ims-tools`.

### What applies to Elisa vs needs 5GC

```text
UE ──N1/N2──► AMF/SMF/UPF     ← Open5GS / free5gc (NOT Elisa)
 │
 └──Gm SIP──► P-CSCF (Elisa)  ← SIPp / Quaff / softphone HERE
              Mw/ISC/Cx …
```

| Test | Elisa? | Needs 5GC? |
|------|--------|------------|
| Gm REGISTER → 401 AKA | Yes | No |
| Authenticated REGISTER → 200 + Path/Service-Route | Yes (HSS-lite + subscriber) | No |
| INVITE MO/MT signaling | Yes | No |
| PDU/IMS DNN, P-CSCF discovery in NAS | No | Yes |
| Rx/N5 QoS / dedicated bearer | No (not Elisa ownership) | Yes + PCRF/PCF |
| RTP | FreeSWITCH/coturn, not Elisa | Media plane |

---

## PCAP probe (mandatory)

Every concrete SIP test against Elisa **must** capture wire and verify with tshark — logs alone are insufficient (prove-on-wire).

### Capture

```bash
sudo tcpdump -i any -s 0 -w loadtest/pcaps/elisa-ims-reg.pcap \
  'udp port 5060 or tcp port 5060'
# run SIPp / smoke in another terminal
# Ctrl-C tcpdump when done
```

On-change script writes: `loadtest/pcaps/on-change-<pid>.pcap` (gitignored blobs; dir kept).

### Wire pass criteria

```bash
tshark -r loadtest/pcaps/on-change-$$.pcap -Y 'sip.Method == REGISTER'
tshark -r loadtest/pcaps/on-change-$$.pcap -Y 'sip.Status-Code == 401'
tshark -r loadtest/pcaps/elisa-ims-reg.pcap -Y sip -V | head
```

| Check | Pass |
|-------|------|
| `sip.Method == REGISTER` count ≥ 1 | Required |
| `sip.Status-Code == 401` | Preferred (AKA challenge); set `IMS_SMOKE_REQUIRE_401=1` to enforce |
| SIPp exit code | Secondary only — **never** alone |

---

## Exact commands to reproduce

### 1. Build SIPp (once)

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/voice-service/sip-freeswitch
env -i HOME="$HOME" PATH=/usr/bin:/bin:/usr/local/bin USER="$USER" bash -lc '
  export CMAKE_ROOT=/usr/share/cmake
  cd loadtest/ims-tools/sipp && mkdir -p build && cd build
  cmake .. -DCMAKE_BUILD_TYPE=Release -DUSE_PCAP=OFF -DUSE_SCTP=OFF
  make -j"$(nproc)" && ./sipp -v
'
```

### 2. Start Elisa lab (:5060 / :8088)

```bash
# Full stack (VIP via nginx :5060 → elisa)
docker compose up -d --build

# Or packaged:
# ensure Postgres reachable, then:
./dist/elisa/run.sh
```

Compose note: `postgres` is internal to `sf-net` only; `nginx` publishes **5060** and admin **8080/8443**. Direct Elisa SIP is on compose service ports when exposed.

### 3. Must-run smoke (PCAP + SIPp)

```bash
./tools/ims-smoke.sh
# equivalent:
./loadtest/ims-tools/run-on-change.sh
```

Env knobs:

| Var | Meaning |
|-----|---------|
| `IMS_SMOKE_ALLOW_SKIP=1` | Exit 0 if :5060 down (do **not** use when claiming SIP fixed) |
| `IMS_SMOKE_REQUIRE_401=1` | Fail unless 401 in pcap |
| `IMS_SMOKE_FORCE=1` | Hook always runs smoke |
| `SIP_HOST` / `SIP_PORT` | Default `127.0.0.1:5060` |
| `PCAP_IFACE` | Default `any` |

### 4. Install pre-push gate

```bash
./tools/install-ims-smoke-hook.sh
```

Chains **after** Digicom attribution `pre-push` (does not replace it). Runs smoke when push range touches `elisa/`, `ra-freeswitch/`, `loadtest/sipp*`, IMS admin HTML, etc.

### 5. Existing CPS harness

```bash
./tools/bench-cps.sh           # needs sipp on PATH + Elisa up
./tools/bench-cps.sh 100 30
# scenario: loadtest/sipp/uac_invite_bye.xml
```

Export PATH to built binary:

```bash
export PATH="$PWD/loadtest/ims-tools/sipp/build:$PATH"
```

---

## Recommended minimal test matrix (Elisa P-CSCF VIP :5060)

| # | Case | Tool | PCAP assert | Expect |
|---|------|------|-------------|--------|
| M1 | Unauth REGISTER | `uac_register_challenge.xml` | REGISTER + 401 | AKA WWW-Authenticate |
| M2 | Bad IMPU | same + unknown user | REGISTER + 403/404 | Reject on wire |
| M3 | INVITE signaling | `uac_invite_bye.xml` | INVITE + 1xx/2xx/4xx | No crash; honest status |
| M4 | CPS ramp | `bench-cps.sh` | sample pcap optional | Capacity doc — do not claim 10k |

Provision subscribers via admin `/admin/subscribers` (or DB `ims_subscriber`) before full AKA 200 matrix.

---

## On-change gate (must-run)

1. Agents/devs: **`./tools/ims-smoke.sh` before claiming SIP/IMS fixed** (see AGENTS.md).
2. Optional hook: `./tools/install-ims-smoke-hook.sh` → pre-push.
3. Fail-closed if Elisa down (`exit 12`) unless `IMS_SMOKE_ALLOW_SKIP=1`.

---

## Gaps / blockers

- Elisa often **not listening** in bare worktree without compose/`run.sh` + DB.
- Compose `postgres` has **no host port** — host `dist/elisa/run.sh` needs `SF_JDBC_URL=jdbc:postgresql://<container-ip>:5432/elisa` or published 5432.
- free5gc / Open5GS do **not** replace CSCF tests.
- Full AKA 200 needs Ki/OPc in HSS-lite + SIPp `[authentication aka_K=… aka_OP=…]`.
- Cursor AppImage PATH breaks system cmake — use clean `env -i` (documented).
- Clearwater live-test needs Ruby; not exercised.
- Large pcaps gitignored under `loadtest/pcaps/` and `ims-tools/`.

---

## Live run note (this session)

| Item | Result |
|------|--------|
| Clones | Under `loadtest/ims-tools/` (SIPp, Open5GS, free5gc, docker_open5gs, Clearwater live-test, Quaff, pyHSS) |
| SIPp compile | **OK** — `loadtest/ims-tools/sipp/build/sipp` |
| On-change gate | `./tools/ims-smoke.sh` + `./tools/install-ims-smoke-hook.sh` installed into `.git/hooks/pre-push` (chains after attribution guard) |
| Elisa lab start | Soft-started `dist/elisa/run.sh` with compose Postgres + `SF_IMS_ISC_AS` / `SF_FS_CONF_DIR` |
| Live PCAP | **Yes** — `loadtest/pcaps/on-change-live.pcap` (Docker `--cap-add=NET_RAW` tcpdump on `lo`) |
| Wire | `sip.Method==REGISTER` **count=4**; `sip.Status-Code==401` **count=0** (REGISTER retransmits; no challenge yet — subscriber/AKA path not answering in this lab boot) |
| Verdict | Suite **usable now = SIPp + PCAP scripts**; 401 matrix still needs provisioned `ims_subscriber` + healthy CSCF REGISTER SBBs |

Host `tcpdump` without CAP_NET_RAW/sudo fails; smoke script falls back to Docker alpine+tcpdump with `NET_RAW`.

### Open5GS × Elisa follow-up (same day)

| Item | Result |
|------|--------|
| Stack | `docker_open5gs` `4g-external-ims-deploy.yaml` up (UPF bridged; `metrics` stopped for :9090) |
| P-CSCF | SMF live config `p-cscf: 192.168.1.13` → host Elisa `:5060` |
| Open5GS subscriber | IMSI `001011234567895` + APN `ims` |
| PCAP dir | `loadtest/pcaps/open5gs-elisa/` (`session-final.pcap`, `SUMMARY.txt`) |
| Wire | REGISTER **32**; **403×6**; **401×0**; Diameter CER **4** / DWR **79** |
| Full E2E | **No** — no UE/RAN; REGISTER not 401/200; INVITE unanswered |
| Report | [`open5gs-elisa-test.md`](open5gs-elisa-test.md) |