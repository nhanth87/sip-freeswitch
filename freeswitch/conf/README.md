# Elisa FreeSWITCH overlays

Bind-mounted over the `safarov/freeswitch` image's vanilla config.

| Overlay | Used by | Purpose |
|---------|---------|---------|
| `autoload_configs/switch.conf.xml` | mvn lab | vanilla + `sessions-per-second` 30→5000, `max-sessions` 1000→20000, loglevel warning, RTP 16384–32768. Vanilla caps the whole lab at ~30 CPS. |
| `autoload_configs/modules.conf.xml` | mvn lab | vanilla + **`mod_xml_curl` loaded** (without it the MRF `sip:1000` dialplan from Elisa `/fs/xml` is never fetched and calls fall into vanilla `user/1000` → fail), `mod_signalwire` off |
| `autoload_configs/event_socket.conf.xml` | compose | ESL `0.0.0.0:8021` (password `ClueCon`) |
| `autoload_configs/event_socket.mvn.conf.xml` | mvn lab | ESL on loopback only (host network) |
| `autoload_configs/xml_curl.conf.xml` | 1-node compose | `http://elisa:8088/fs/xml` |
| `autoload_configs/xml_curl.scscf.conf.xml` | split / N–N FS-1 | `http://scscf:8088/fs/xml` |
| `autoload_configs/xml_curl.scscf-2.conf.xml` | N–N FS-2 | `http://scscf-2:8088/fs/xml` |
| `autoload_configs/xml_curl.mvn.conf.xml` | mvn lab | `http://127.0.0.1:8088/fs/xml` |
| `sip_profiles/external.xml` | compose | external Sofia profile on SIP `5060` |
| `sip_profiles/external.mvn.xml` | mvn lab | `127.0.0.1:5062` (Elisa owns `:5060`), no STUN |
| `sip_profiles/disabled.xml` | mvn lab | mounted over vanilla `internal*.xml` / `external-ipv6.xml` — vanilla internal also binds `:5060` |

mvn lab (Elisa from Maven on the host, PG + FS in Docker): [`../docker-compose.yml`](../docker-compose.yml).

Never point xml_curl at the nginx VIP (`/fs/` is denied on the edge). See [`docs/agents/docker-lab.md`](../../docs/agents/docker-lab.md).
