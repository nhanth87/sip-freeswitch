# Elisa Production P0 — go-live checklist

**Status:** proven on lab nn (2026-08-11). Packaged `dist/elisa/` + REGISTER 401 + INVITE 200 + `nn-midcall-kill.sh` PASS + `/admin/ims` peer truth. Not Clearwater parity; not 10k CPS; charging stays off; not a Digicom USSDGW host claim.

| Prove | Evidence |
|-------|----------|
| REGISTER first hop **401** | [`loadtest/pcaps/p0-register-401.pcap`](../../loadtest/pcaps/p0-register-401.pcap) (`sip.Status-Code==401`) |
| INVITE **200** + ACK + BYE | [`loadtest/pcaps/p0-invite-200.pcap`](../../loadtest/pcaps/p0-invite-200.pcap) |
| Mid-call `docker stop scscf` → BYE **200** | [`loadtest/pcaps/nn-midcall-kill-20260811-144106.pcap`](../../loadtest/pcaps/nn-midcall-kill-20260811-144106.pcap) — `./loadtest/scripts/nn-midcall-kill.sh` exit 0 (`bye=2 bye200=2 sipp_rc=0`) |
| Admin peer truth | `GET /admin/partial/ims-status`: `eslLive=true` (ESL heartbeat), `cxPeerReady=local HSS-lite OK`, `ispnMode=DIST_SYNC` N–N. Never LISTEN-as-UP. |

Ship unit: **`dist/elisa/`** only (`./tools/package-dist.sh` → `run.sh`). JDK **25**. Never clobber host `configs/` casually.

---

## 1. Package

```bash
# JDK 25 (mise zulu-25)
./tools/package-dist.sh
# → dist/elisa/{run.sh,elisa-app.jar,quarkus-run.jar,lib/,quarkus/,app/html/,configs/,logs/}
```

- Fast-jar layout; **no jars under `app/html/`**
- Log4j2 → `logs/` via packaged `configs/log4j2.xml`
- Activate prod profile on host: `QUARKUS_PROFILE=prod` + `deploy/elisa/roles/prod.env`

---

## 2. Secrets / admin (fail-closed)

| Knob | Prod |
|------|------|
| `sf.admin.dev-login` | **false** (`SF_ADMIN_DEV_LOGIN=false`) |
| `SF_ADMIN_API_KEY` | required (no lab default in prod profile) |
| `SF_ADMIN_SESSION_HMAC_SECRET` | required ≠ `change-me-sf` |
| `SF_ADMIN_BOOTSTRAP_PASSWORD` | required ≠ `admin` |

Refusing bootstrap `admin`/`admin` when `dev-login=false` + default password. Template: [`deploy/elisa/roles/prod.env`](../../deploy/elisa/roles/prod.env).

---

## 3. IMS roles + features (ops)

| Flag | Default | Notes |
|------|---------|-------|
| `sf.ims.role.p/i/s/hss` | lab combined | Split via `deploy/elisa/roles/*.env` |
| `sf.ims.feature.mmtel` | true | iFC/MMTel-lite ISC gate |
| `sf.ims.feature.charging` | **false** | Master for dual-write / SessionS / Rf |
| `sf.ims.feature.enum` | **false** | Stub — no NAPTR yet |

Admin `/admin/ims`: roles + features + **cxPeerReady** (CER/CEA) + **eslLive** (ESL heartbeat) + ISPN mode. Never treat LISTEN / RA started as link UP.

---

## 4. Charging (flagged off)

Off until operator enables `sf.ims.feature.charging` **and** peer knobs:

- Offline: `sf.charging.dual-write` + `sf.cgrates.*` + `sf.rf.enabled`
- Online: `sf.cgrates.sessions.enabled` / `sf.ocs.session.enabled` + `sf.ocs.mode=deferred|live`
- Rf: Diameter ACR via `ra-diameter` when `peerReady`; else structured log fallback
- Deferred debit: PG `sf_pending_charge` + claim-by-icid (no double-debit)

**Never:** PFCP/N4, Gx, Gy, Gx-over-Gy, RTP through Elisa, SBC.

---

## 5. Cluster / media / ingress

| Item | Prod expectation |
|------|------------------|
| ISPN | **DIST_SYNC** for multi S-CSCF; `LOCAL` = single-node lab only |
| PG | Dedicated Elisa DB; Flyway through V7 (`sf_pending_charge` + IMS CDR + MMTel) |
| FS pairs | `sf_fs_pair` + ESL live truth |
| nginx | L4 SIP VIP + HTTPS admin; RTP/TURN bypass — [`nginx-ingress.md`](nginx-ingress.md) |
| Pool | `microjainslee.container.sbb-pool-max=40960` |

---

## 6. Prove the artifact (before “redeployed OK”)

1. Package `dist/elisa/`
2. Rsync jars / `bin` / `app/html` — **not** host `configs/` unless asked
3. Restart → `:8088` ready
4. Jar/html mtime or `jar tf` / classpath proves new classes
5. Hit broken surface: `/admin/ims` (peerReady + eslLive), SIP path if charging on

Green `mvn test` alone ≠ host runs new code.

---

## 7. Explicit non-claims

- No 10 000 INVITE/s until SIPp green ([`capacity-acceptance.md`](capacity-acceptance.md))
- No Chronos / full Clearwater / Sh / Ut / Rx / ENUM NAPTR
- No Digicom “fixed” without §6

Pointers: [`elisa-improve-plan.md`](elisa-improve-plan.md) · [`ims-protocol-roadmap.md`](ims-protocol-roadmap.md) · [`packaging.md`](packaging.md)
