# Capacity — 64 cores / 128 GiB / 10 TB, target 10 Gbit/s

Honest split: **control plane (Java 25 + micro-jainslee)** vs **user plane (non-JVM)**. No claim is closed without the bake-off harness.

Hardware in this note: dual-socket class box, **64 cores**, **128 GiB RAM**, **10 TB disk**, at least **2×10 GbE** (S1-U/S5-U/SGi). That is enough metal for **10 Gbit/s full-duplex** on the data plane if — and only if — packets never enter the JVM.

```text
                    Java 25 / micro-jainslee                 DPDK / kernel GTP
  MME ──S11──► SGW-C ──PFCP──► SGW-U ──GTP-U──► PGW-U ──SGi── Internet
  HSS ◄─S6a──  PGW-C ──Gx/Gy                                 (64C / 128G)
               ~few k CPS                                    10 Gbit/s packets
```

## 1. What Java 25 + micro-jainslee can own

| Function | Plane | 64C/128G expectation | Not this plane |
|----------|-------|----------------------|----------------|
| HSS S6a/Cx (`diameter-ra` + Postgres SQN) | CP | Tens of k AIR/ULR/s if SQN is `UPDATE … RETURNING` and Ki stays in HSM | Packet forwarding |
| SGW-C / PGW-C (`gtpv2c-ra` + `pfcp-ra`) | CP | Create/Modify/Delete session in the low thousands/s per node with DIST_SYNC + dual-write off hot path | GTP-U, NAT, QoS bytes |
| PCEF Gx/Gy/Gz | CP | Session + CDR correlation; Gy RTT-bound | Per-packet charging in Java |
| Elisa SIP (`sip-servlet-ra`) | IMS CP | 10k INVITE/s is a **signaling** target, unrelated to 10 Gbit/s media | RTP (FreeSWITCH/TURN) |

Virtual threads help **session concurrency**, not packet I/O. Infinispan `epc-cluster` and Postgres checkpoints must stay **off** the GTP-U path (already the design). Disk 10 TB is for CDR/PCAP/backups, not the forwarding fast path.

**Verdict (CP):** a 64C box can run several SGW-C + PGW-C + HSS JVMs comfortably for a 10k-subscriber / 10 Gbit/s **pilot traffic mix**. It will **not** push 10 Gbit/s through Netty/Java.

## 2. Linux kernel GTP (owned baseline)

Typical community/Open5GS-class kernel GTP on a well-tuned box: **hundreds of Mbit/s to ~2 Gbit/s**, dominated by TUN/qdisc, iptables, and core frequency — not by “64 cores”. RSS/multi-queue and `gtp` module offload can improve this; **10 Gbit/s full-duplex with NAT + charging counters is not assumed**.

Use it as the **correctness** UP. If the 10 Gbit/s gate fails, that is expected — escalate to DPDK.

## 3. DPDK / VPP — can this box do 10 Gbit/s?

**Yes, with margin — as a user-plane, not as a JVM.**

Order-of-magnitude (industry-typical, not our measurement):

| Workload | Cores (rule of thumb) | 64C box |
|----------|----------------------|---------|
| 10 Gbit/s IMIX, no crypto, poll-mode NIC | 2–4 PMD + 2–4 workers | Easy |
| 10 Gbit/s + CGNAT + ACL + per-bearer counters | 8–16 | Comfortable |
| 10 Gbit/s + IPsec/ESP | more, still ≪ 64 | Feasible |
| 40–100 Gbit/s | many PMDs, NUMA-aware | Possible later; not the 10G gate |

128 GiB is ample for 10k–100k bearer contexts in hugepages. 10 TB disk is irrelevant to PMD loops (keep CDR on CP Postgres).

**Conditions:**

- NIC with DPDK PMD (mlx5/i40e/ice class), VFIO, 1G hugepages, CPU isolation (`isolcpus` + `irqaffinity`)
- One NUMA node owns the 10GbE NIC + PMD threads
- PFCP from SGW-C/PGW-C programs VPP/DPDK; Java stays on S11/S5-C
- Full-duplex + uplink **and** downlink; mixed packet sizes; 10k contexts
- NextEPC AccUPF 30 Gbit/s PDF is **5G N3/N6 downlink-only** — it does not close this 4G 10G gate

## 4. Recommended split on this server

| Cores | Role |
|-------|------|
| 2–4 | OS / interrupts / SSH |
| 4–8 | Postgres `epc` + HSS JVM |
| 4–8 | SGW-C JVM (`gtpv2c-ra` + `pfcp-ra` + `epc-cluster`) |
| 4–8 | PGW-C JVM + PCEF + `diameter-ra` Gx/Gy |
| 4–8 | Elisa (if co-located; prefer separate IMS hosts at scale) |
| **16–32 isolated** | DPDK/VPP SGW-U+PGW-U (or combined UP) |
| remainder | headroom / second NUMA |

Do **not** run DPDK PMDs and the JIT on the same isolated cores.

## 5. What we will not claim yet

- 10 Gbit/s on Linux GTP
- 10 Gbit/s through Java/Netty
- NextEPC 30G as proof of 4G S1-U/S5-U
- “SGW/PGW HA” or “VoLTE ready”

Close the gate only with [`epc/loadtest/bakeoff/`](../../loadtest/bakeoff/README.md) on this class of hardware: full-duplex 10 Gbit/s, 10k contexts, IPv4+IPv6, charging reconcile, process-kill recovery.
