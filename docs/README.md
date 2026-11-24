# Docs — layout

Every Markdown document of the repo lives here. Only the files that tools read in place stay
outside: `README.md`, `AGENTS.md`, `CLAUDE.md` at the root, `epc/AGENTS.md` (agent instructions
are directory-scoped) and the `dist/*/README.md` that `tools/package-dist*.sh` generate.

| Folder | What |
|---|---|
| [`agents/`](agents/) | Elisa / IMS core design and runbooks: architecture, IMS core (P/I/S-CSCF), HSS links, VoLTE chain, capacity, packaging, lessons |
| [`epc/agents/`](epc/agents/) | EPC design: architecture, 64-core / 10G capacity, PCEF, VoLTE Rx |
| [`epc/nni/`](epc/nni/) | EPC host-MNO interface: charging, interface contract, PICS Rel-14, RACI, NextEPC evaluation |
| [`epc/loadtest/`](epc/loadtest/) | EPC load tests, bake-off, open5gs gap analyses and plans |
| [`loadtest/`](loadtest/) | SIPp harness, IMS tools, pcap evidence layout |
| [`freeswitch/`](freeswitch/) | FreeSWITCH lab overlay and conf |
| [`deploy/`](deploy/) | Host deployment notes (EPC Linux GTP-U) |
| [`readiness/`](readiness/) | EPC + IMS production-readiness review and P1 response |
| [`status/`](status/) | [`PENDING-ISSUES.md`](status/PENDING-ISSUES.md) — open items and what landed |
| [`3gpp/`](3gpp/) | 3GPP specifications used by the code (Markdown + PDF) |
| [`presentations/`](presentations/) | Decks |
