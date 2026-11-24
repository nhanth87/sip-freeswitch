# FreeSWITCH lab overlays

Bind-mounted from root `docker-compose.yml`:

- `conf/autoload_configs/event_socket.conf.xml` — ESL (password ClueCon)
- `conf/autoload_configs/xml_curl.conf.xml` — fetch from elisa:8088/fs/xml
- `conf/sip_profiles/external.xml` — external Sofia profile

```bash
# from monorepo root
docker compose up -d freeswitch
```

Not the 10k CPS plane — Elisa owns signaling rate.
