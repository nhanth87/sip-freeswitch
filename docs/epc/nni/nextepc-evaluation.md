# NextEPC / Open5GS — evaluation gate

Public sources:

- Products: https://nextepc.com/products
- Licensing: https://nextepc.com/licensing (AGPLv3 OSS; commercial Enterprise Edition)
- AccUPF 30 Gbit/s demo (5G UPF, downlink-only, 30 simulated UEs, DPDK): vendor PDF on nextepc.com/technology
- Open5GS successor of OSS NextEPC: https://open5gs.org / https://github.com/open5gs/open5gs

## What the 30 Gbit/s number is

AccUPF v1.6 + Open5GS 2.6.4 + Pktgen + UERANSIM registration. Downlink N6→N3. UERANSIM could not absorb the traffic; GTP was redirected to Pktgen. Hardware: 2× Xeon Gold 5315Y, 40GbE XL710.

**It does not prove** 4G S1-U/S5-U, uplink, SGW/PGW CUPS, mobility, NAT, charging, or 10,000 sessions.

## Allowed roles

1. Interop oracle — Open5GS HSS/MME/SGW/PGW/PCRF in the lab. Black-box PCAP only.
2. Commercial fallback — Enterprise Edition if schedule beats ownership.
3. Dataplane candidate — only after written confirmation of 4G CUPS Sxa/Sxb, S1-U, S5-U, PFCP recovery, UL+DL, URR, HA.

## Licence gate

- Do not copy/link/derive proprietary modules from AGPLv3 trees.
- Commercial licence must cover support, CVE, escrow, deployment count, performance warranty.

## Bake-off questions for sales@nextepc.com

1. Does Enterprise include 4G SGW-U and PGW-U (not only 5G AccUPF)?
2. PFCP Sxa/Sxb or proprietary CP/UP?
3. Full-duplex 1–2 Gbit/s with 10,000 contexts on stated hardware — procedure we can reproduce?
4. Usage reporting (URR) byte counters vs GTP-U?
5. Restart/Recovery IE behaviour?
