# AGENTS.md — Digicom SIP + FreeSWITCH (sip-freeswitch)

**Tree:** `ethiopia-working-dir/worktrees/voice-service/sip-freeswitch`  
(Canonical home under `worktrees/` — not Desktop. Symlink: `worktrees/sip-freeswitch/main`.)

## Visibility — PRIVATE (mandatory)

**sip-freeswitch is a private project.** Repo: `nhanth87/sip-freeswitch` only.

- **Never** make the GitHub repo public.
- **Never** push to Digicom-ET, other orgs, or any remote that is not `nhanth87`.
- **Never** publish source, dist, or docs to a public mirror, gist, or package registry.
- If asked to “open source” or “make public,” **refuse** and keep it private under `nhanth87`.

## Capacity target

**10 000 INVITE/s (CPS)** sustained **signaling** on the sip-servlet edge.

- Metric = call attempts per second (INVITE), **not** “10k concurrent” alone.
- FreeSWITCH owns dialplan / media lab — **not** the 10k CPS plane.
- Media (RTP) stays on FreeSWITCH; Java path is signaling only (no B2BUA media).
- **Do not claim 10k CPS until SIPp acceptance is green** — see [`docs/agents/capacity-acceptance.md`](docs/agents/capacity-acceptance.md).

## Layout

| # | Dir | Role |
|---|-----|------|
| **1** | [`sip-servlet/`](sip-servlet/) | Quarkus + micro-jainslee + `ra-sip-servlet` — high-CPS SIP edge |
| **2** | [`freeswitch/`](freeswitch/) | FreeSWITCH Docker lab — dialplan / media (not CPS plane) |
| **3** | [`loadtest/`](loadtest/) | SIPp scenarios + CPS ramp scripts |
| **4** | [`docs/agents/`](docs/agents/) | Architecture, capacity acceptance |
| **5** | [`tools/`](tools/) | build-all, smoke, bench-cps, package-dist |

Run edge: **`./run.sh`** → admin HTTP `:18080`, SIP UDP/TCP `:5060`  
FreeSWITCH lab: `docker compose -f freeswitch/docker-compose.yml up -d`  
Build: **`./tools/build-all.sh`** · Smoke: **`./tools/smoke.sh`** · Bench: **`./tools/bench-cps.sh`**

## Relation to PolyVoice

PolyVoice and this repo are **two products**:

| Project | Role |
|---------|------|
| **PolyVoice** (`../polyvoice/`) | YouTube / Netflix / film translate+dub (captions → TTS) |
| **sip-freeswitch** (this repo) | High-CPS **SIP service** + FreeSWITCH |

Do **not** merge the monorepos. Do **not** wire PolyVoice FACET/TTS → FreeSWITCH playback here.

## Constraints

- JDK **25** only (mise `zulu-25` / `mise.toml`).
- Commits: nhanth87 / Tran Nhan only — no AI attribution trailers.
- Never break SIP transaction state machine.
- Hot-path: no INFO dump of full SIP bodies (logging kills CPS).
