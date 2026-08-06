# sip-freeswitch

Private Digicom SIP edge: **Quarkus + `ra-sip-servlet`** targeting **10 000 INVITE/s**, with **FreeSWITCH** as dialplan/media lab.

> **PRIVATE** — `nhanth87/sip-freeswitch` only. Never public.

## Quick start

```bash
# JDK 25
mise install
./tools/build-all.sh
./run.sh
# admin: http://127.0.0.1:18080/admin/
# SIP:   udp/tcp 5060
```

FreeSWITCH lab (optional):

```bash
docker compose -f freeswitch/docker-compose.yml up -d
```

Load ramp (needs `sipp`):

```bash
./tools/bench-cps.sh
```

## Architecture

```
SIPp / carriers  --INVITE×N cps-->  sip-servlet (Quarkus cluster)
                                         |
                                    trunk/proxy
                                         v
                                   FreeSWITCH (dialplan + RTP)
```

See [`AGENTS.md`](AGENTS.md) and [`docs/agents/architecture.md`](docs/agents/architecture.md).
