# sip-freeswitch / Elisa

Private Digicom SIP edge (**Elisa**) + FreeSWITCH + PostgreSQL. Target **10 000 INVITE/s**.

> **PRIVATE** — `nhanth87/sip-freeswitch` only.

## Quick start

```bash
docker compose up -d --build
# or local:
mise install
./tools/build-all.sh
./run.sh
# http://127.0.0.1:8088/admin/  (admin/admin when sf.admin.dev-login=true)
```

See [`AGENTS.md`](AGENTS.md) and [`docs/agents/`](docs/agents/).
