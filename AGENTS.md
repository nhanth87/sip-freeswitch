# AGENTS.md — Digicom SIP + FreeSWITCH (sip-freeswitch / Elisa)

**Tree:** `ethiopia-working-dir/worktrees/voice-service/sip-freeswitch`  
Symlink: `worktrees/sip-freeswitch/main`. Chat worktree: `worktrees/sip-freeswitch/chat`.

## Visibility — PRIVATE (mandatory)

**sip-freeswitch is a private project.** Repo: `nhanth87/sip-freeswitch` only.

- Never make public; never push Digicom-ET/other remotes; never public mirrors.

## Role — SIP only (not an SBC)

**Elisa is a high-CPS SIP signaling edge.** It is **not** an SBC.

| Owns | Does **not** own |
|------|------------------|
| SIP transaction / dialog (RFC 3261), trunk toward FreeSWITCH | RTP/SRTP relay, media anchoring, codec transcoding |
| Admin HTTP, ESL, xml_curl, ACL, CDR ingest | Topology hiding / full B2BUA SBC product features |
| 3GPP SIP header awareness for future IMS/5G/6G signaling | P-CSCF / I-CSCF / S-CSCF, Diameter Cx/Rx as product |

**Media** stays on FreeSWITCH (+ TURN/ICE on the media path). **Ingress later:** nginx (or stream) in front of admin HTTP; SIP L4 only if needed — Elisa remains the SIP state machine.

See [`docs/agents/architecture.md`](docs/agents/architecture.md), [`docs/agents/3gpp-sip-ims.md`](docs/agents/3gpp-sip-ims.md).

## Capacity target

**10 000 INVITE/s (CPS)** on the **Elisa** SIP edge. FreeSWITCH = dialplan/media (not CPS plane).
Do not claim 10k until SIPp green — [`docs/agents/capacity-acceptance.md`](docs/agents/capacity-acceptance.md).

## Layout

| # | Dir | Role |
|---|-----|------|
| **1** | [`elisa/`](elisa/) | Quarkus + micro-jainslee + `ra-sip-servlet` — SIP edge + HTMX admin |
| **2** | [`freeswitch/`](freeswitch/) | FreeSWITCH conf overlays + lab |
| **3** | [`app/html/`](app/html/) | Disk admin UI ({{TOKEN}}, HTMX) |
| **4** | [`loadtest/`](loadtest/) | SIPp CPS harness |
| **5** | [`docs/agents/`](docs/agents/) | architecture, 3gpp-sip-ims, admin-ui, media-ice-turn, … |
| **6** | [`tools/`](tools/) | build-all, smoke, bench-cps, package-dist |

```bash
docker compose up -d --build   # postgres + freeswitch + elisa
./run.sh                       # local elisa :8088 / SIP :5060
```

Admin: `http://127.0.0.1:8088/admin/` (dev `admin`/`admin` when `sf.admin.dev-login=true`).

## Relation to PolyVoice

| Project | Role |
|---------|------|
| PolyVoice | YouTube / Netflix / film translate+dub |
| sip-freeswitch (Elisa) | High-CPS SIP + FreeSWITCH |

Do not merge. Do not wire PolyVoice TTS → FreeSWITCH playback.

## Constraints

- JDK **25** only (mise `zulu-25`).
- Commits: nhanth87 / Tran Nhan only — no AI attribution.
- Never break SIP transaction state machine.
- Hot-path: no INFO SIP body dumps.
- Admin UI standard: [`docs/agents/admin-ui.md`](docs/agents/admin-ui.md) (OTA sim-push pattern).
- Media: **rtp_redirect** (default on) — ICE + TURN relay so RTP crosses the firewall; FS `bypass_media=false`. Elisa does not relay RTP. See [`docs/agents/media-ice-turn.md`](docs/agents/media-ice-turn.md).
- **Not an SBC**; SIP only; nginx in front later — [`docs/agents/architecture.md`](docs/agents/architecture.md).
