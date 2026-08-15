# AGENTS.md — nhanth87 SIP + FreeSWITCH (sip-freeswitch / Elisa)

**Tree:** `ethiopia-working-dir/worktrees/voice-service/sip-freeswitch`  
Symlink: `worktrees/sip-freeswitch/main`. Chat worktree: `worktrees/sip-freeswitch/chat`.

## Visibility — PRIVATE (mandatory)

**sip-freeswitch is a private project.** Repo: `nhanth87/sip-freeswitch` only.

- Never make public; never push Digicom-ET/other remotes; never public mirrors.

## Role — lab IMS Core (not an SBC)

**Elisa is a feature-flagged lab IMS Core** (P/I/S-CSCF + optional HSS-lite) plus **mandatory FreeSWITCH** MRF/AS + ESL control. It is **not** an SBC.

### FreeSWITCH — essential (hard lock)

**FreeSWITCH is a required product/lab component — never omit or document “Elisa without FS” as a supported full-IMS path.** Soft-fail ESL (`eslLive=false` when FS is down) is OK for process start; compose/profiles must always include `freeswitch` (and `freeswitch-2` under N–N, **1:1 with each S-CSCF**). Voice / ICE / TURN / xml_curl / ISC AS all assume FS. Do **not** drop `ra-freeswitch` or make FS optional via feature flags for “full IMS”.

| Owns | Does **not** own |
|------|------------------|
| SIP transaction / dialog (RFC 3261) via **`ra-sip-servlet`** | RTP/SRTP relay, media anchoring, codec transcoding |
| CSCF roles (Gm/Mw/ISC) when `sf.ims.role.*` enabled | Topology hiding / full B2BUA SBC product features |
| Diameter **Cx** via **`ra-diameter`** + HSS-lite (Postgres) | PCRF/PCF Rx/N5, MGCF/BGCF (later phases) |
| FreeSWITCH control via **`ra-freeswitch`** (ESL) — **mandatory** | FreePBX as signaling controller |
| Admin HTTP, xml_curl, ACL, CDR, IMS subscribers | |

**Media** stays on FreeSWITCH as **essential** MRF/AS (+ TURN/ICE). **Ingress (locked):** nginx in front — HTTPS admin + SIP L4 VIP; RTP/TURN bypass nginx — [`docs/agents/nginx-ingress.md`](docs/agents/nginx-ingress.md). Elisa keeps the SIP state machine.

Grill locks I1–I8: [`docs/agents/ims-core.md`](docs/agents/ims-core.md). Also [`architecture.md`](docs/agents/architecture.md), [`nginx-ingress.md`](docs/agents/nginx-ingress.md), [`3gpp-sip-ims.md`](docs/agents/3gpp-sip-ims.md), [`ra-freeswitch.md`](docs/agents/ra-freeswitch.md).

## Planned RA split (grill-locked direction)

```text
UE ──Gm──► P-CSCF ──Mw──► I-CSCF ──Mw──► S-CSCF ──ISC──► FreeSWITCH (MRF/AS)
                              │              │
                           Cx UAR/LIR     Cx MAR/SAR
                              └──────► HSS-lite / external HSS
S-CSCF SBBs ──► ra-freeswitch ──ESL :8021──► FreeSWITCH
              (3-port: RaEndpointPort + RaCommandPort + RaBootstrapPort)
```

| RA | Name (inject) | Plane | Spec |
|----|---------------|-------|------|
| **`ra-sip-servlet`** | `sip-servlet-ra` | SIP Gm/Mw/ISC | Vendor RA; never break SIP transaction SM. **Elisa only — not epc-host.** |
| **`ra-diameter`** | `diameter-ra` | Diameter Cx **and** S6a/Gx/Gy | Vendor RA; `isPeerReady()` = CER/CEA. No second Diameter stack. |
| **`ra-freeswitch`** | `freeswitch-ra` | FreeSWITCH ESL control | Vendor RA — JAINSLEE 3-port |
| **`gtpv2c-ra` / `pfcp-ra`** | vendor `ra-gtpv2c` / `ra-pfcp` | GTPv2-C / PFCP | EPC CP only; GTP-U stays non-JVM |

### `ra-freeswitch` — mandatory shape (do not invent outside this)

- **Contract:** `RaEndpointPort` + `RaCommandPort` + `RaBootstrapPort` (same as other micro-jainslee RAs).
- **Outbound commands (sealed):** e.g. `FsApi`, `FsBgapi`, `FsSubscribe`, `FsOriginate` (as needed) — SBB → RA only via `RaCommandPort.sendCommand`.
- **Inbound events:** e.g. `FsChannelEvent`, `FsHeartbeatEvent`, `FsApiResponseEvent` — RA → SLEE via `RaBootstrapPort.fireEvent`.
- **Transport:** ESL (Event Socket) to FreeSWITCH `:8021`. Soft-fail when FS down (`eslLive=false`); never fake REGED. **FS itself remains mandatory in compose** — soft-fail ≠ optional component.
- **Not in this RA:** SIP INVITE/BYE (stays `ra-sip-servlet`); RTP; xml_curl HTTP (stays Quarkus REST unless grill moves it).
- **Home:** `jain-slee/vendor-ras/ra-freeswitch` (`com.microjainslee:ra-freeswitch`). Elisa depends on the vendor artifact.
- **Phase 1 surface (grill B=1+2):**
  1. ESL sync — `FsApi` / `FsBgapi` + `FsApiResponseEvent` (replace ad-hoc `EslClient`)
  2. ESL inbound events — subscribe `HEARTBEAT` + `CHANNEL_*` → `FsHeartbeatEvent` / `FsChannelEvent`
- **Activity model (grill C=2+3):**
  - **`fs-link`** — one long-lived activity for `HEARTBEAT` + `FsApiResponseEvent`; `endActivity` on RA deactivate
  - **`fs-ch-{Unique-ID}`** — per-channel activity for `CHANNEL_*`; `endActivity` on `CHANNEL_HANGUP` / `CHANNEL_HANGUP_COMPLETE` / `CHANNEL_DESTROY`
- **Not in Phase 1:** `FsOriginate` / hangup helpers (grill later); xml_curl stays Quarkus HTTP.
- **JDK 25 only.** Hot-path: no INFO dumps of full ESL bodies.

### Home

Shipped as vendor `jain-slee/vendor-ras/ra-freeswitch`. Elisa wires it in `ElisaBootstrap` next to SIP RA (`@InjectRa(name = "freeswitch-ra")`). Thin `et.elisa.esl.EslClient` as admin/ESL tabs use the RA.

## Capacity target

**10 000 INVITE/s (CPS)** on the **Elisa** SIP edge (`ra-sip-servlet`) is the **per-node claim gate** — do not claim 10k until SIPp green ([`docs/agents/capacity-acceptance.md`](docs/agents/capacity-acceptance.md)).
**1 000 000 SIP TPS** is a **cluster horizon** (many P-CSCF + sharded S-CSCF), not one JVM and not RTP — [`docs/agents/capacity-1m.md`](docs/agents/capacity-1m.md). Do not claim 1M.
FreeSWITCH = dialplan/media (not CPS plane). `ra-freeswitch` is **control plane** — not counted in CPS.

## Layout

| # | Dir | Role |
|---|-----|------|
| **1** | [`elisa/`](elisa/) | Quarkus + micro-jainslee + vendor RAs (`ra-sip-servlet`, `ra-freeswitch`, `ra-diameter`) |
| **1c** | [`epc/`](epc/) | 4G Full MVNO EPC (HSS/SGW-C/PGW-C) — **separate process**, same git repo. GTP/PFCP RAs are vendor `ra-gtpv2c` / `ra-pfcp`. |
| **2** | [`freeswitch/`](freeswitch/) | FreeSWITCH conf overlays + lab |
| **3** | [`app/html/`](app/html/) | Disk admin UI ({{TOKEN}}, HTMX) |
| **4** | [`loadtest/`](loadtest/) | SIPp CPS harness |
| **5** | [`docs/agents/`](docs/agents/) | architecture, multi-service-deploy (1 code → P/I/S/HSS + N–N), ra-freeswitch, … |
| **6** | [`tools/`](tools/) | build-all, smoke, bench-cps, package-dist, package-dist-epc |

```bash
docker compose up -d --build   # postgres + freeswitch + elisa
./run.sh                       # local elisa :8088 / SIP :5060
```

Admin: `http://127.0.0.1:8088/admin/` (dev `admin`/`admin` when `sf.admin.dev-login=true`).

## Relation to ChipChipVoice

| Project | Role |
|---------|------|
| ChipChipVoice (`../chipchipvoice/`) | YouTube / Netflix / film translate+dub — **no SS7 / no SIP** |
| sip-freeswitch (Elisa) | High-CPS SIP + FreeSWITCH |

Do not merge. Do not wire ChipChipVoice TTS → FreeSWITCH playback.

## Do-not-miss checklist (Elisa)

- **Java 25 only** — mise `zulu-25`. Never Java 8/11/17/21. Module name is **`elisa/`** (not sip-servlet).
- **Ship only `dist/elisa/`** — after `./tools/package-dist.sh`, ship **`dist/elisa/`** alone → `dist/elisa/run.sh`. Never deploy the monorepo / `elisa/src/`. Rsync jars/`bin`/`lib`/`app/html` as packaged — **never** overwrite host `configs/` casually. → [packaging](docs/agents/packaging.md)
- **Never sandbox `package-dist` / `mvn package|test` / `tools/build-all`** — Shell **`required_permissions: ["all"]`** on the **first** call; do not fail-then-retry. → [lessons](docs/agents/lessons.md)
- **Prove the artifact, not the source (NON-NEGOTIABLE — SIẾT)** — Green `mvn test` / new unit tests / source greps **NEVER** mean a remote or local host runs the new Elisa code. **Forbidden** to report “fixed” / “done” / “redeployed OK” until **all** of:
  1. **Package** — `./tools/package-dist.sh` (or `./tools/build-all.sh` then package) — JDK 25.
  2. **Ship bits only** — copy/rsync the packaged `dist/elisa/` runtime (jars / `bin` / UI) — **never** clobber operator `configs/` without asking.
  3. **Restart + ready** — restart Elisa → wait **`:8088`** admin/health ready (compose `elisa` service or `dist/elisa/run.sh`).
  4. **Artifact on host** — jar/html **mtime** vs change, or `jar tf` / class presence for the new symbol; running PID classpath.
  5. **Live broken surface** — hit the admin/SIP/ESL path that failed (e.g. `/admin/...`, Sofia/ESL status) — health alone may not prove the bug. Never trust `mvn -q test` alone. → [lessons](docs/agents/lessons.md) · [packaging](docs/agents/packaging.md)
- **IMS SIP on-change smoke + PCAP (lab)** — Before claiming SIP/IMS REGISTER/INVITE fixed locally, run **`./tools/ims-smoke.sh`** (fail-closed if `:5060` down). Must prove on wire with tshark (`sip.Method==REGISTER` / `sip.Status-Code==401`), not logs alone. Tools live under [`loadtest/ims-tools/`](loadtest/ims-tools/). Install optional pre-push: `./tools/install-ims-smoke-hook.sh`. Report: [`docs/agents/ims-external-test-report.md`](docs/agents/ims-external-test-report.md). Not Digicom production prove.

- **Session/identity SIẾT (shared)** — in-flight PK ≠ user/profile PK; rehydrate wipes heap CAS; stamp peer gen to session; no per-tick write storms; Digicom/Quarkus = PG bake + rsync runtime only (never `configs/`). → skill `digicom-et-host` § Session / identity SIẾT · parent [`docs/agents/lessons.md`](../../../docs/agents/lessons.md)

## Constraints

- JDK **25** only (mise `zulu-25`).
- Commits: nhanth87 / Tran Nhan only — no AI attribution.
- Never break SIP transaction state machine.
- Hot-path: no INFO SIP body dumps.
- Admin UI standard: [`docs/agents/admin-ui.md`](docs/agents/admin-ui.md) (OTA sim-push pattern).
- Media: **rtp_redirect** (default on) — ICE + TURN relay so RTP crosses the firewall; FS `bypass_media=false`. Elisa does not relay RTP. See [`docs/agents/media-ice-turn.md`](docs/agents/media-ice-turn.md).
- **FreeSWITCH essential** — always in compose (`depends_on` for `elisa` / `scscf`); N–N = 1:1 S↔FS. Soft-fail ESL OK; omitting FS is **not** a supported production/lab path. → [`docker-lab.md`](docs/agents/docker-lab.md) · [`ra-freeswitch.md`](docs/agents/ra-freeswitch.md)
- **Not an SBC**; SIP only; nginx edge VIP — [`docs/agents/nginx-ingress.md`](docs/agents/nginx-ingress.md) · [`docs/agents/architecture.md`](docs/agents/architecture.md).
- **RA split:** `ra-sip-servlet` = SIP; **`ra-freeswitch`** = ESL/control (JAINSLEE 3-port) — **do not drop**. See Planned RA split above + [`docs/agents/ra-freeswitch.md`](docs/agents/ra-freeswitch.md).
- **EPC is a sibling process, not Elisa.** [`epc/`](epc/) owns HSS/AuC (S6a + production Cx), SGW-C/PGW-C (GTPv2-C + PFCP), Linux GTP UP. Never put GTP-U / S11 / S6a / PFCP / PCEF inside the Elisa JVM. Cluster name `epc-cluster` must not join `elisa-cluster`. Dist: `./tools/package-dist-epc.sh` → `dist/epc/`. Compose profile `epc`. Detail: [`epc/AGENTS.md`](epc/AGENTS.md) · [`docs/agents/epc.md`](docs/agents/epc.md).
