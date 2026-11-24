# Linux kernel GTP baseline

Owned SGW-U/PGW-U path. Java `epc-up-linux` emits `ip` / `gtp-tunnel` recipes; a privileged helper applies them. The JVM **never** forwards GTP-U, NAT, or QoS.

Requires `gtp` kernel module on the UP host. Bake-off vs VPP/DPDK and NextEPC is `epc/loadtest/bakeoff/`.
