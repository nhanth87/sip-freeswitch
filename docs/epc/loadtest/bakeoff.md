# User-plane bake-off

Compare **Linux kernel GTP** (owned baseline), **VPP/DPDK**, and **NextEPC Enterprise/AccUPF** only after written confirmation of 4G CUPS (`Sxa/Sxb`, `S1-U`, `S5-U`, uplink+downlink, usage reporting).

Harness gates (no claim until measured on identical hardware):

- 10 000 subscriber contexts, full-duplex 1–2 Gbit/s, mixed packet sizes
- IPv4 and IPv6; never downlink-only / Pktgen-only
- Mobility, NAT attribution, charging counters, failure recovery
- NextEPC 30 Gbit/s AccUPF PDF is 5G downlink-only — it does **not** close this gate

Scripts here fail closed until a lab run writes `results/<date>.json`.
