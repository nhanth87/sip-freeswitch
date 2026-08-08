# AGENTS.md — nhanth87 SIP + FreeSWITCH (sip-freeswitch / Elisa)

**Tree:** `ethiopia-working-dir/worktrees/voice-service/sip-freeswitch`  
Symlink: `worktrees/sip-freeswitch/main`. Chat worktree: `worktrees/sip-freeswitch/chat`.

## Visibility — PRIVATE (mandatory)

**sip-freeswitch is a private project.** Repo: `nhanth87/sip-freeswitch` only.

- Never make public; never push Digicom-ET/other remotes; never public mirrors.

## Role — SIP only (not an SBC)

**Elisa is a high-CPS SIP signaling edge.** It is **not** an SBC.

| Owns | Does **not** own |
|------|------------------|
| SIP transaction / dialog (RFC 3261) via **`ra-sip-servlet`** | RTP/SRTP relay, media anchoring, codec transcoding |
| FreeSWITCH control via **`ra-freeswitch`** (ESL / FS events — JAINSLEE 3-port) | Topology hiding / full B2BUA SBC product features |
| Admin HTTP, xml_curl (HTTP), ACL, CDR ingest | P-CSCF / I-CSCF / S-CSCF, Diameter Cx/Rx as product |
| 3GPP SIP header awareness for future IMS/5G/6G signaling | |

**Media** stays on FreeSWITCH (+ TURN/ICE on the media path). **Ingress later:** nginx (or stream) in front of admin HTTP; SIP L4 only if needed — Elisa remains the SIP state machine.

See [`docs/agents/architecture.md`](docs/agents/architecture.md), [`docs/agents/3gpp-sip-ims.md`](docs/agents/3gpp-sip-ims.md), [`docs/agents/ra-freeswitch.md`](docs/agents/ra-freeswitch.md).

## Planned RA split (grill-locked direction)

```text
UA ──SIP──► ra-sip-servlet ──SIP trunk──► FreeSWITCH (Sofia)
                 │
Elisa SBBs ──► ra-freeswitch ──ESL :8021──► FreeSWITCH (Event Socket)
                 │                 (api / bgapi / events)
              (3-port: RaEndpointPort + RaCommandPort + RaBootstrapPort)
```

| RA | Name (inject) | Plane | Spec |
|----|---------------|-------|------|
| **`ra-sip-servlet`** | `sip-servlet-ra` | SIP signaling (UA + trunk) | Existing vendor RA; never break SIP transaction SM |
| **`ra-freeswitch`** | `freeswitch-ra` | FreeSWITCH control / status | **New internal RA** — JAINSLEE 3-port; replace ad-hoc `EslClient` CDI |

### `ra-freeswitch` — mandatory shape (do not invent outside this)

- **Contract:** `RaEndpointPort` + `RaCommandPort` + `RaBootstrapPort` (same as other micro-jainslee RAs).
- **Outbound commands (sealed):** e.g. `FsApi`, `FsBgapi`, `FsSubscribe`, `FsOriginate` (as needed) — SBB → RA only via `RaCommandPort.sendCommand`.
- **Inbound events:** e.g. `FsChannelEvent`, `FsHeartbeatEvent`, `FsApiResponseEvent` — RA → SLEE via `RaBootstrapPort.fireEvent`.
- **Transport:** ESL (Event Socket) to FreeSWITCH `:8021`. Soft-fail when FS down (`eslLive=false`); never fake REGED.
- **Not in this RA:** SIP INVITE/BYE (stays `ra-sip-servlet`); RTP; xml_curl HTTP (stays Quarkus REST unless grill moves it).
- **Home (grill A=1):** implement under sip-freeswitch as **internal module** [`ra-freeswitch/`](ra-freeswitch/); promote to `jain-slee/vendor-ras/ra-freeswitch` when stable.
- **Phase 1 surface (grill B=1+2):**
  1. ESL sync — `FsApi` / `FsBgapi` + `FsApiResponseEvent` (replace ad-hoc `EslClient`)
  2. ESL inbound events — subscribe `HEARTBEAT` + `CHANNEL_*` → `FsHeartbeatEvent` / `FsChannelEvent`
- **Activity model (grill C=2+3):**
  - **`fs-link`** — one long-lived activity for `HEARTBEAT` + `FsApiResponseEvent`; `endActivity` on RA deactivate
  - **`fs-ch-{Unique-ID}`** — per-channel activity for `CHANNEL_*`; `endActivity` on `CHANNEL_HANGUP` / `CHANNEL_HANGUP_COMPLETE` / `CHANNEL_DESTROY`
- **Not in Phase 1:** `FsOriginate` / hangup helpers (grill later); xml_curl stays Quarkus HTTP.
- **JDK 25 only.** Hot-path: no INFO dumps of full ESL bodies.

### Migration

1. Scaffold `ra-freeswitch` (endpoint + config + ESL transport + sealed command/event).
2. Wire in `ElisaBootstrap` next to SIP RA; `@InjectRa(name = "freeswitch-ra")` in status/admin SBBs or services adapted to SBB/command style.
3. Delete / thin `et.elisa.esl.EslClient` once admin/ESL tabs use the RA.
4. Document in `docs/agents/ra-freeswitch.md` + sequence diagrams for command/event flows.

## Capacity target

**10 000 INVITE/s (CPS)** on the **Elisa** SIP edge (`ra-sip-servlet`). FreeSWITCH = dialplan/media (not CPS plane).
`ra-freeswitch` is **control plane** — not counted in CPS.
Do not claim 10k until SIPp green — [`docs/agents/capacity-acceptance.md`](docs/agents/capacity-acceptance.md).

## Layout

| # | Dir | Role |
|---|-----|------|
| **1** | [`elisa/`](elisa/) | Quarkus + micro-jainslee + `ra-sip-servlet` — SIP edge + HTMX admin |
| **1b** | [`ra-freeswitch/`](ra-freeswitch/) | Internal JAINSLEE RA — ESL control (`freeswitch-ra`) |
| **2** | [`freeswitch/`](freeswitch/) | FreeSWITCH conf overlays + lab |
| **3** | [`app/html/`](app/html/) | Disk admin UI ({{TOKEN}}, HTMX) |
| **4** | [`loadtest/`](loadtest/) | SIPp CPS harness |
| **5** | [`docs/agents/`](docs/agents/) | architecture, ra-freeswitch, 3gpp-sip-ims, media-ice-turn, … |
| **6** | [`tools/`](tools/) | build-all, smoke, bench-cps, package-dist |

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

## Constraints

- JDK **25** only (mise `zulu-25`).
- Commits: nhanth87 / Tran Nhan only — no AI attribution.
- Never break SIP transaction state machine.
- Hot-path: no INFO SIP body dumps.
- Admin UI standard: [`docs/agents/admin-ui.md`](docs/agents/admin-ui.md) (OTA sim-push pattern).
- Media: **rtp_redirect** (default on) — ICE + TURN relay so RTP crosses the firewall; FS `bypass_media=false`. Elisa does not relay RTP. See [`docs/agents/media-ice-turn.md`](docs/agents/media-ice-turn.md).
- **Not an SBC**; SIP only; nginx in front later — [`docs/agents/architecture.md`](docs/agents/architecture.md).
- **RA split:** `ra-sip-servlet` = SIP; **`ra-freeswitch`** = ESL/control (JAINSLEE 3-port). See Planned RA split above + [`docs/agents/ra-freeswitch.md`](docs/agents/ra-freeswitch.md).
