# Elisa FreeSWITCH overlays

The compose file retains the `safarov/freeswitch` image's default configuration and bind-mounts only
these overlays: ESL on `8021` (password `ClueCon`), XML curl, and the external Sofia profile on SIP `5060`.

| Overlay | Used when | `gateway-url` |
|---------|-----------|---------------|
| `xml_curl.conf.xml` | 1-node lab | `http://elisa:8088/fs/xml` |
| `xml_curl.scscf.conf.xml` | split / N–N FS-1 | `http://scscf:8088/fs/xml` |
| `xml_curl.scscf-2.conf.xml` | N–N FS-2 | `http://scscf-2:8088/fs/xml` |

Never point xml_curl at `localhost` or the nginx VIP (`/fs/` is denied on the edge). See [`docs/agents/docker-lab.md`](../../docs/agents/docker-lab.md).
