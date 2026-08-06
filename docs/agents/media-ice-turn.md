# Media path — ICE / TURN / rtp_redirect (firewall)

## Goal

**RTP must cross the firewall** via a controlled path (`rtp_redirect`). Elisa stays **SIP-only** (no RTP hairpin). Redirect = TURN relay (+ FreeSWITCH media ports), not peer-to-peer host/srflx-only.

```text
UA  --SIP-->  Elisa  --SIP-->  FreeSWITCH
UA  --RTP-->  coturn (TURN relay)  <-->  FreeSWITCH RTP
              ↑ firewall allows TURN + FS RTP range
```

## Policy (defaults)

| Knob | Default | Effect |
|------|---------|--------|
| `sf.media.rtp-redirect` | `true` | Forces ICE + TURN `typ relay`; reject otherwise with **488 RTP redirect (TURN relay) required** |
| `sf.media.ice-required` | `true` | INVITE SDP without ICE → **488 ICE required** |
| `sf.media.require-turn` | `true` | Same TURN rule; implied when `rtp-redirect=true` |
| FS `inbound-bypass-media` | `false` | No UE↔UE direct media |
| Dialplan (xml_curl) | `bypass_media=false` | Exported on each bridged call |

## Lab stack

`docker compose` runs **coturn** on `:3478` and publishes FreeSWITCH RTP `16384-16484/udp`.

- Client: `GET /media/ice-config` (`rtpRedirect: true`)
- FreeSWITCH Sofia `external`: ICE + accept peer TURN relays; **no bypass**
- Elisa does **not** originate or forward RTP packets

## ICE selection

`IceNegotiationSbb` prefers **relay** over srflx/host when TURN/rtp_redirect is on.

## Admin

`/admin/codecs` (nav **ICE/TURN**) shows `rtp_redirect`; `/admin/status` includes `rtpRedirect`.

## Note

Signaling ACL (`/admin/acl`) is separate from media path. Both must pass for a call.
