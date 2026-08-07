# ra-freeswitch — FreeSWITCH control RA

JAINSLEE **internal RA** (sip-freeswitch module) for Elisa ↔ FreeSWITCH **control plane** (ESL). SIP stays on `ra-sip-servlet`.

## Grill locks

| Q | Decision |
|---|----------|
| **A** | **1** — Internal module [`ra-freeswitch/`](../../ra-freeswitch/) first; promote to `jain-slee/vendor-ras/ra-freeswitch` later |
| **B** | **1+2** — ESL sync (`FsApi`/`FsBgapi` + `FsApiResponseEvent`) **and** inbound `HEARTBEAT` / `CHANNEL_*` events |
| **C** | **2+3** — link activity `fs-link` for HEARTBEAT/API; per-channel `fs-ch-{uuid}` for `CHANNEL_*` (end on hangup/destroy) |

## 3-port contract

```mermaid
sequenceDiagram
  participant SBB
  participant Cmd as RaCommandPort
  participant RA as ra-freeswitch
  participant FS as FreeSWITCH_ESL
  participant Boot as RaBootstrapPort
  SBB->>Cmd: FsApi(requestId, "sofia status")
  Cmd->>RA: sendCommand
  RA->>FS: api sofia status
  FS-->>RA: +OK ...
  RA->>Boot: fireEvent(FsApiResponseEvent)
  Boot->>SBB: onEvent
  Note over RA,FS: inbound socket
  FS-->>RA: Event-Name: HEARTBEAT
  RA->>Boot: fireEvent(FsHeartbeatEvent)
```

| Port | Role |
|------|------|
| `RaEndpointPort` | `activate` / `deactivate`; ESL connect lifecycle; name `freeswitch-ra` |
| `RaCommandPort` | Sealed `FsOutboundCommand` |
| `RaBootstrapPort` | `createActivityHandle`, `fireEvent`, `endActivity` |

## Activity model (C=2+3)

| Activity id | Events | End |
|-------------|--------|-----|
| `fs-link` | `FsHeartbeatEvent`, `FsApiResponseEvent` | RA `deactivate` |
| `fs-ch-{Unique-ID}` | `FsChannelEvent` | `CHANNEL_HANGUP` / `CHANNEL_HANGUP_COMPLETE` / `CHANNEL_DESTROY` |

## Commands / events (Phase 1)

| Direction | Type |
|-----------|------|
| Out | `FsApi(requestId, command)`, `FsBgapi(requestId, command)`, `FsSubscribe(events…)` |
| In | `FsApiResponseEvent`, `FsHeartbeatEvent`, `FsChannelEvent` |

## Non-goals

- Not an SBC / not RTP
- Not a second SIP stack toward FS (trunk = `ra-sip-servlet`)
- xml_curl stays Quarkus REST

## Replace

`et.elisa.esl.EslClient` → bridge to RA sync helper, then SBB/`@InjectRa(name="freeswitch-ra")`.
