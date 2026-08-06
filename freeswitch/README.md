# FreeSWITCH lab (dialplan / media)

**Not the 10k CPS plane.** FreeSWITCH owns dialplan + RTP. sip-servlet edge trunks here.

## Compose

```bash
docker compose -f freeswitch/docker-compose.yml up -d
```

| Port | Role |
|------|------|
| `5062/udp+tcp` | FreeSWITCH SIP (host) — avoids clash with edge `:5060` |
| `8021` | ESL (lab) |
| `16384-16484/udp` | RTP range (lab-sized) |

## Trunk peer

Edge default (`application.properties`):

```
sip.trunk.freeswitch-uri=sip:1000@127.0.0.1:5062
sip.trunk.domain=freeswitch.lab
```

Point FreeSWITCH Sofia external profile / ACL at the host IP running sip-servlet `:5060` when bridging real calls.

## Capacity note

Scale media with FreeSWITCH (or a farm). Claim **10 000 INVITE/s** only against the Java edge via SIPp — see `docs/agents/capacity-acceptance.md`.
