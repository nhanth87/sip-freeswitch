# 4G attach interop — srsUE + srsENB + open5gs MME against our core (2026-11-25)

Owner rule: from open5gs / free5gc only the RAN side (srsUE, srsENB over ZMQ) and the MME / AMF. HSS/AuC,
SGW-C, PGW-C, PCRF and the UP are ours.

```text
 srsUE --ZMQ-- srsENB .30 --S1AP/SCTP--> open5gs MME .20 --S6a--> epc-hss .11 (HSS / AuC, Postgres)
                  |                         \--S11--> epc-sgwc .14 --S5-C--> epc-pgwc .13 --Gx--> epc-pcrf .12
                  \--S1-U--> epc-up-linux .16 <---------- Sxa (SGW-C) / Sxb (PGW-C)
```

## Run

```bash
(cd epc && mvn -o -pl epc-host -am package -DskipTests)
docker compose -f docker-compose.yml -f docker-compose.services.yml -f docker-compose.epc-wire.yml \
  -f docker-compose.epc-ran.yml --profile epc build epc-hss epc-sgwc epc-pgwc epc-pcrf
docker build -t docker_srslte loadtest/ims-tools/docker_open5gs/srslte     # srsRAN 4G with ZMQ (~40 min once)
cp epc/loadtest/ran/ue.env.example epc/loadtest/ran/ue.env                 # TS 35.208 test set, git-ignored
./epc/loadtest/scripts/epc-seed-subscriber.sh                              # IMSI 001010000000001 in our HSS
./epc/loadtest/ran/ran-attach.sh
```

## Result

srsUE: `Network attach successful. IP: 10.64.0.2` (address from our PGW-C). MME: `Attach complete`, default bearer EBI 5.
Capture `loadtest/pcaps/ran/ran-attach.pcap` (git-ignored), 0 malformed:

S1 Setup → Attach Request → **AIR / AIA** (our AuC; the UE accepts AUTN, Milenage) → Authentication → Security Mode →
**ULR / ULA** → ESM information → **S11 Create Session** → S5-C → **Gx CCR-I / CCA** → **Sxb / Sxa Establishment** →
Create Session Response → Initial Context Setup / Attach Accept → Attach Complete → **Modify Bearer** → **Sxa Session
Modification** (Update FAR toward the eNB).

## What the real MME found (all fixed, each with a test)

| # | Defect | Fix |
|---|---|---|
| 1 | AIA put the vectors in AVP 1490 / 1491 — the MME found no Authentication-Info (TS 29.272: 1413 / 1414; 1490 is IDR-Flags) | `S6aAvp` codes, Item-Number per vector, IDR-Flags 1490 |
| 2 | Every S6a answer also carried its raw view through the string map: a second Result-Code `"2001"` and RAND / XRES / AUTN / KASME as hex strings at the top level | `S6aDiameterMapper.toAnswer`: typed AVPs only |
| 3 | freeDiameter: "FIXED_HEAD AVP appears first in (2) position … 'Session-Id'" | ra-diameter: Session-Id first (RFC 6733 §8.8) |
| 4 | freeDiameter: "number of occurences (0) is < the rule min (1) for 'Origin-Host'" | ra-diameter: Origin-Host / Origin-Realm in every request and answer (RFC 6733 §6.2) |

## Open

| # | Item |
|---|---|
| R-1 | Create Session took 7.5 s on a just-started, loaded lab (cold JVMs, srsRAN ZMQ on the same box): the MME retransmitted twice (correctly de-duplicated, one PGW session), the first CCA arrived after the 2 s Tx timer (local policy used). Re-measure warm. |
| R-2 | User plane: ping through the UE tunnel needs the kernel GTP device in `epc-up-linux`; not exercised yet. |
| R-3 | 5G (AMF + srsUE SA) needs a 5GC on our side (AUSF/UDM/SMF/UPF); not in scope yet. |
