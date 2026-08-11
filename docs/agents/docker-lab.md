# Docker lab — Elisa + Postgres + FreeSWITCH + coturn (+ nginx)

**JDK 25** build on the host first (SNAPSHOT micro-jainslee is not rebuilt inside the Elisa image).

**FreeSWITCH is essential.** Every full voice/IMS lab compose must include `freeswitch` (`elisa`/`scscf` `depends_on` it). N–N adds `freeswitch-2` **1:1 with `scscf-2`**. Soft-fail ESL (`eslLive=false` when FS is down) does **not** make FS optional — never document “run Elisa without FreeSWITCH” as a supported production path.

```bash
./tools/build-all.sh          # → elisa/target/quarkus-app/
docker compose up -d --build  # 1-node lab (always includes freeswitch)
```

Ship-to-host Digicom still uses `./tools/package-dist.sh` → `dist/elisa/` — this doc is **compose lab only**.

---

## Service map

### 1-node lab (`docker-compose.yml`)

| Service | Role | Wire |
|---------|------|------|
| `postgres` | Elisa DB | JDBC `jdbc:postgresql://postgres:5432/elisa` |
| `coturn` | STUN/TURN | `:3478` host + `sf-net`; RTP/TURN **bypass nginx** |
| `freeswitch` | **Essential** MRF/AS + ESL | ESL `:8021` (pwd `ClueCon`); SIP `freeswitch:5060`; RTP `16384-16484` — **required**; `elisa` `depends_on` it |
| `elisa` | P+I+S+HSS combined | JDBC→PG; ESL→`freeswitch`; TURN→`coturn`; trunk `sip:1000@freeswitch:5060` |
| `nginx` | Edge VIP | HTTP `:8080`/TLS `:8443` → elisa:8088; SIP `:5060` → elisa:5060 |

```text
UE ──SIP VIP──► nginx ──► elisa (combined)
                 │ ESL + ISC trunk
                 ▼
            freeswitch     RTP ◄── coturn (no nginx)
                 │ xml_curl
                 ▼
            http://elisa:8088/fs/xml
```

### N–N / split (`docker-compose.yml` + `docker-compose.services.yml`)

| Service | Profile | Pair / plane |
|---------|---------|--------------|
| `postgres` | (always) | Shared SoT for all roles |
| `coturn` | (always) | Shared TURN for all FS/UEs |
| `pcscf` | `services` | Gm VIP pool; ISPN `LOCAL` |
| `icscf` | `services` | Mw + Cx → `hss` |
| `scscf` | `services` | S-1; `DIST_SYNC`; ESL→`freeswitch` (**depends_on**); xml_curl ← FS-1 |
| `scscf-2` | `services`+`nn` | S-2; same `SF_CLUSTER_ISPN_NAME`; ESL→`freeswitch-2` (**depends_on**) |
| `freeswitch` | **(always — essential)** | Overlay remaps xml_curl → `scscf:8088`; never scale to 0 for full voice lab |
| `freeswitch-2` | `services`+`nn` | **Essential** second pair; ESL/SIP on `sf-net` only; xml_curl → `scscf-2:8088` |
| `hss` | `services` | Diameter Cx |
| `nginx-services` | `services` | VIP → **pcscf** (hash affinity); admin → **scscf** |

```text
UE ──SIP VIP──► nginx-services ──► P×N ──Mw──► I×N ──Cx──► HSS
                                    │              │
                                    │ Mw           │
                                    ▼              │
                                 S×N ◄─────────────┘   (home sticky + ISPN DIST_SYNC)
                                  │ ESL 1:1 + ISC trunk
                                  ▼
                                 FS×N     RTP ◄── coturn (shared, no nginx)
```

Pairing (locked C2/C3):

| Elisa node id | ESL host | Trunk URI | xml_curl |
|---------------|----------|-----------|----------|
| `elisa-scscf-1` | `freeswitch:8021` | `sip:1000@freeswitch:5060` | `http://scscf:8088/fs/xml` |
| `elisa-scscf-2` | `freeswitch-2:8021` | `sip:1000@freeswitch-2:5060` | `http://scscf-2:8088/fs/xml` |

`SF_CLUSTER_ISPN_MODE=DIST_SYNC` and `SF_CLUSTER_ISPN_NAME=elisa-cluster` on **S-CSCF only**. P/I/HSS stay `LOCAL`.

---

## Commands

### 1-node lab

```bash
./tools/build-all.sh
docker compose up -d --build
docker compose ps
```

### Split services (1×S + 1×FS)

```bash
./tools/build-all.sh
docker compose -f docker-compose.yml -f docker-compose.services.yml \
  --profile services up -d --build --scale elisa=0 --scale nginx=0
```

### N–N (2×S + 2×FS)

```bash
./tools/build-all.sh
docker compose -f docker-compose.yml -f docker-compose.services.yml \
  --profile services --profile nn up -d --build --scale elisa=0 --scale nginx=0
```

Scale more P later: `docker compose … --scale pcscf=2` (nginx.services.conf already hashes `pcscf:5060`; add upstream peers for multi-IP).

### N–N mid-call kill-test (fail-closed)

After `services`+`nn` is up and SIP VIP answers:

```bash
./loadtest/scripts/nn-midcall-kill.sh
# INVITE 200 → docker stop scscf → BYE 200 must appear in pcap or exit 1
```

Does **not** prove Chronos / media remap. RTP stays on the home FreeSWITCH pair until BYE.

---

## Smoke — prove ESL / TURN / SIP

```bash
# Health (1-node: via nginx; direct container also ok)
curl -fsS http://127.0.0.1:8080/q/health

# ESL live truth (LISTEN ≠ ready) — admin status JSON
curl -fsS http://127.0.0.1:8080/admin/status | tee /tmp/elisa-status.json
# expect eslLive=true when FS ESL answers; sofia snippet present

# IMS / peer surface (split: admin on scscf via nginx-services)
# open http://127.0.0.1:8080/admin/ims  → eslLive + cxPeerReady

# TURN listening (bypass nginx)
ss -uln | grep 3478 || true
docker compose exec coturn turnutils_uclient -v -u elisa -w elisaturn coturn 2>/dev/null | head || true

# FreeSWITCH ESL from host (1-node maps 8021)
docker compose exec freeswitch fs_cli -x "sofia status"
# N–N second pair:
docker compose --profile services --profile nn exec freeswitch-2 fs_cli -x "sofia status" 2>/dev/null || true

# ICE admin (rtp_redirect / TURN knobs)
# http://127.0.0.1:8080/admin/codecs  (nav ICE/TURN)
curl -fsS http://127.0.0.1:8080/media/ice-config
```

**ESL soft-fail:** if FreeSWITCH is temporarily down, Elisa still starts; `eslLive=false` until ESL connects. Soft-fail ≠ optional: compose **must** still declare and start `freeswitch` / `freeswitch-2`. Password must match `freeswitch/conf/autoload_configs/event_socket.conf.xml` (`ClueCon`).

**Unsupported:** running a “full IMS” voice lab with FreeSWITCH omitted from compose/profiles, or documenting Elisa-only (no FS) as production-ready media.

**xml_curl:** always `http://<paired-s-cscf>:8088/fs/xml` on `sf-net` — never `localhost`, never nginx VIP (`/fs/` is denied on the edge).

---

## Env cheat sheet (Elisa)

| Env | Lab value |
|-----|-----------|
| `SF_JDBC_URL` | `jdbc:postgresql://postgres:5432/elisa` |
| `SF_ESL_HOST` / `PORT` / `PASSWORD` | `freeswitch` / `8021` / `ClueCon` |
| `SF_TRUNK_FREESWITCH_URI` | `sip:1000@freeswitch:5060` |
| `SF_MEDIA_TURN_URI` | `turn:coturn:3478` (+ user/cred) |
| `SF_MEDIA_REQUIRE_TURN` / `RTP_REDIRECT` | `true` |
| `SF_CLUSTER_ISPN_MODE` | `LOCAL` (1-node) / `DIST_SYNC` (S×N) |

---

## Related

- [`media-ice-turn.md`](media-ice-turn.md) · [`nginx-ingress.md`](nginx-ingress.md) · [`ra-freeswitch.md`](ra-freeswitch.md)
- [`cmp-recovery.md`](cmp-recovery.md) · [`sbb-recovery-manager.md`](sbb-recovery-manager.md)
- [`multi-service-deploy.md`](multi-service-deploy.md) §100-server · [`cluster-c2-vip-affinity.md`](cluster-c2-vip-affinity.md)
- Role env: [`deploy/elisa/roles/`](../../deploy/elisa/roles/)
