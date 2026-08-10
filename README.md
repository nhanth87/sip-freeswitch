# sip-freeswitch / Elisa

Private nhanth87 SIP edge (**Elisa**) + FreeSWITCH + PostgreSQL. Target **10 000 INVITE/s**.

> **PRIVATE** — `nhanth87/sip-freeswitch` only.

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

Ingress design: [`docs/agents/nginx-ingress.md`](docs/agents/nginx-ingress.md). See [`AGENTS.md`](AGENTS.md) and [`docs/agents/`](docs/agents/).
