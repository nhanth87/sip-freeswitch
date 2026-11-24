# Architecture — epc (inside sip-freeswitch monorepo)

```text
Host eNB ──S1-MME── Host MME ──S6a──► DRA/DRE (external) ──► MVNO HSS/AuC
              │
              └──S11──► SGW-C ──Sxa/PFCP──► SGW-U ◄──S1-U── Host eNB
                           │                   │
                           └──S5-C──► PGW-C    └──S5-U──► PGW-U ──SGi── PDNs
                                        │                   │
                                   Gx/Gy/Gz              IMS APN → Elisa
                                        │
                                   PCRF / OCS / OFCS
```

Java 25 owns control plane (HSS, SGW-C, PGW-C, RAs). User plane is a non-JVM process programmed over PFCP.

Elisa is IMS only. Production Cx goes to this HSS, not HSS-lite.
