# sip-freeswitch / Elisa

<!-- knowledge-vault:start -->
## Knowledge base

This project's docs are mirrored and cross-linked in the shared Obsidian vault: `~/Desktop/knowledge-vault/telecom-project/Projects/Ethiopia/Elisa (sip-freeswitch)/Elisa (sip-freeswitch) — Index.md` (home: `~/Desktop/knowledge-vault/telecom-project/Home.md`). Search there first; the files in this repo stay the source of truth.
<!-- knowledge-vault:end -->

Private nhanth87 SIP edge (**Elisa** IMS) + **epc/** (4G MVNO HSS/SGW/PGW, separate process) + FreeSWITCH + PostgreSQL. Elisa target **10 000 INVITE/s**.

> **PUBLIC** — `nhanth87/sip-freeswitch` only (no other remotes).

## Quick start

```bash
docker compose up -d --build
# edge (nginx → Elisa):  http://127.0.0.1:8080/admin/  HTTPS :8443
# SIP VIP:               127.0.0.1:5060  (TCP/UDP → nginx stream → elisa)
# TURN (bypass nginx):   127.0.0.1:3478
# or local without nginx:
mise install
./tools/build-all.sh
./run.sh
# http://127.0.0.1:8088/admin/  (admin/admin when sf.admin.dev-login=true)
```

Ingress design: [`docs/agents/nginx-ingress.md`](docs/agents/nginx-ingress.md). EPC plane: [`docs/agents/epc.md`](docs/agents/epc.md). See [`AGENTS.md`](AGENTS.md) and [`docs/agents/`](docs/agents/).

```bash
# EPC control-plane lab (dedicated postgres-epc; not the Elisa DB)
docker compose -f docker-compose.yml -f docker-compose.services.yml --profile epc config
```
