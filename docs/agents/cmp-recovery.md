# CMP recovery — SBB die → ISPN/PG → continue call

**Status:** lab implemented (app-level CMP + dual write).  
**Related:** [`jainslee-profile.md`](jainslee-profile.md) (shared ProfileFacility), [`datastore.md`](datastore.md), micro-jainslee `CmpBackedSbb` / `@CmpField`.  
**Orchestrator:** [`sbb-recovery-manager.md`](sbb-recovery-manager.md) — **PARTIAL** (local respawn + fencing + portable DialogRegistry peers **+ lab synthetic wire restore**).

---

## 1. Goal

When an SBB entity dies (passivate/fail/recreate), **call and user correlate state must be recoverable** so mid-call signalling can continue. Elisa persists invite/session CMP to:

| Tier | Store | Key |
|------|--------|-----|
| Hot | Infinispan via shared `ElisaInfinispanManager` (`DialogCheckpointStore` / `CallSessionStore`) | `sipCallId` / `fsUuid` |
| Durable | Postgres `sf_dialog_checkpoint` / `sf_call_session` | same |
| Not SoT | SLEE `CallSessionProfile` (JVM-local Facility) | advisory mirror |

Load order on restore: **ISPN → PG** (rehydrate ISPN on PG hit). Multi-node: set `sf.cluster.infinispan.mode=DIST_SYNC` so all Elisa share one `sf.cluster.infinispan.cluster-name`.

---

## 2. CMP fields (JAIN SLEE 1.1 §6.5)

### `ScscfInviteSbb` (extends `CmpBackedSbb`)

| CMP | Notes |
|-----|--------|
| `sipCallId` | Entity correlate / Send* Call-ID |
| `fsUuid` | FS Unique-ID (filled on CHANNEL_*) |
| `msisdn` | Resolved identity |
| `fromUri` / `toUri` | Dialog parties |
| `trunkUri` | ISC AS / MT contact target |
| `peer` | Optional ingress peer |
| `fsNodeId` / `elisaNodeId` | Affinity |
| `dialogState` | `INVITE` → `EARLY` → `CONFIRMED` → `TERMINATED` |
| `startedAtEpochMs` / `updatedAtEpochMs` | Timestamps |

Concrete class: `ScscfInviteSbb.$Concrete` (local map + `cmpWrite` when container store bound).

### `CallSessionSbb`

| CMP | Notes |
|-----|--------|
| `fsUuid`, `sipCallId`, `msisdn` | Correlate |
| `fromUri`, `toUri`, `direction` | FS headers |
| `fsNodeId`, `elisaNodeId` | Nodes |
| `dialogState` | CREATE / ANSWER / HANGUP… |
| timestamps | |

Write-through: `CallSessionService` (ISPN + PG + Profile) and `DialogCmpPersistence` link FS uuid back onto dialog CMP.

---

## 3. Sequence — SBB die → recover → continue

```mermaid
sequenceDiagram
  participant UE
  participant SBB as ScscfInviteSbb
  participant CMP as DialogCmpPersistence
  participant ISPN as DialogCheckpoint ISPN
  participant PG as sf_dialog_checkpoint
  participant RA as ra-sip-servlet DialogRegistry
  participant FS as FreeSWITCH

  UE->>SBB: INVITE
  SBB->>SBB: set CMP fields
  SBB->>CMP: save (write-through)
  CMP->>ISPN: put(callId)
  CMP->>PG: upsert row
  SBB->>RA: SendInvite(callId)
  Note over RA: DialogRegistry JVM-local peers

  Note over SBB: SBB entity dies / new node

  UE->>SBB: mid-dialog (BYE / re-INVITE / 200)
  SBB->>CMP: load(callId)
  CMP->>ISPN: get
  alt ISPN miss
    CMP->>PG: findById
    CMP->>ISPN: rehydrate
  end
  SBB->>SBB: restoreCmp → CMP fields
  SBB->>RA: SendResponse/SendBye(callId)
  Note over RA: Lab HA: restorePortable rebuilds<br/>peers + synthetic lastRequest from SipDialogHa
```

**App recovery API:** `ScscfInviteSbb.recoverAfterFailure(sipCallId)` / `CallSessionSbb.recoverAfterFailure(fsUuid)` — used in unit tests; production path is `sbbActivate` + `ensureCmpLoaded` on mid-dialog events. Failover also calls `restoreDialogFromCheckpoint` / `restoreDialogFromCluster`.

---

## 4. Write-through points

| Event | Action |
|-------|--------|
| INVITE | CMP = INVITE; `DialogCmpPersistence.save` |
| 1xx | CMP = EARLY; save |
| 2xx / SDP | CMP = CONFIRMED; save |
| 4xx+ / BYE | CMP = TERMINATED; `remove` (ISPN drop + PG `ended_at`) |
| CHANNEL_CREATE/ANSWER | `CallSessionService.upsertFromFs` + dialog CMP `withFs` |
| CHANNEL_HANGUP | end session + dialog CMP remove |

---

## 5. RA HA (lab mid-dialog)

`ra-sip-servlet` **`DialogRegistry` is still a JVM-local map**, but portable meta is dual-written:

| Path | What |
|------|------|
| RA sticky | `publishHaOpened` → `RaSessionMeta` attrs (`PortableDialogMeta`) when `ClusterManager` bound |
| Elisa CMP | `DialogCmpPersistence` enricher exports RA portable meta into `DialogCheckpoint.sipHa` / PG columns on every CMP save |
| Failover restore | `restoreDialogFromCluster` **or** `SbbRecoveryManagerImpl.restoreDialogFromCheckpoint` → `DialogRegistry.restorePortable` rebuilds peers **and** synthetic `lastRequest` / far `lastResponse` when URI+tag essentials are present |

| Scenario | App CMP | Mid-call SendResponse / SendBye |
|----------|---------|----------------------------------|
| SBB recreate **same JVM**, DialogRegistry intact | Yes | Yes |
| Fail over to **other S-CSCF** with DIST_SYNC checkpoint + SipDialogHa | Yes | **Lab yes** (synthetic wire from tags/contacts) |
| Checkpoint without tags (pre-enrich / early INVITE only) | Yes | Peers only — Send* still needs first recovered inbound |

Honest limits: not Clearwater Chronos; no full NIST dialog SM; TCP connection affinity still needs L4/VIP for mid-dialog *inbound* to the new owner; ProfileFacility remains JVM-local.

### Follow-up

1. Wire shared `ClusterManager` into SIP RA so sticky FORWARD_REMOTE / RaSessionMeta matches Elisa ISPN membership.
2. IES Call-ID convergence + container `CmpFieldStore` dual-write.
3. Soft-hang heartbeat (R8 P2).

Until tags are checkpointed, sticky L4 affinity remains the safety net — see [`cluster-c2-vip-affinity.md`](cluster-c2-vip-affinity.md).

---

## 6. Code map

| Piece | Path |
|-------|------|
| CMP snapshot | `et.elisa.cmp.InviteCmpSnapshot` |
| Dual persist | `et.elisa.cmp.DialogCmpPersistence` |
| ISPN record | `et.elisa.cluster.DialogCheckpoint` (+ `dialogState` + `SipDialogHa`) |
| PG entity | `et.elisa.persist.SfDialogCheckpoint` / table `sf_dialog_checkpoint` (+ V4 SIP HA cols) |
| Invite SBB | `et.elisa.ims.scscf.ScscfInviteSbb` |
| Session SBB | `et.elisa.session.CallSessionSbb` |
| Tests | `et.elisa.cmp.InviteCmpRoundTripTest`, `DialogCmpSipHaRestoreTest`, RA `DialogRegistryRestoreOutboundTest` |

---

## 7. Grill note vs Profile (C9)

- **ProfileFacility** (`CallSessionProfile` / `ImsSubscriberProfile`) = shared hot map across SBBs.  
- **SBB CMP** = entity-local dialog handler state + `profileKey`/`sipCallId`/`fsUuid` pointers.  
Both dual-write in lab; CMP recovery is the path when the **handler entity** dies; Profile remains the cross-SBB lookup.
