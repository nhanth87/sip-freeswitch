# IMS test tools (Elisa lab)

Canonical download dir for external IMS / VoLTE / SIP CSCF test suites used against **Elisa** (lab IMS Core — P/I/S-CSCF + HSS-lite). **Not** Digicom production prove.

Path: `loadtest/ims-tools/`

| Subdir | Upstream | Compile | Hits Elisa today? |
|--------|----------|---------|-------------------|
| `sipp/` | https://github.com/SIPp/sipp | **Yes** — `build/sipp` (clean env cmake) | **Yes** — Gm REGISTER/INVITE |
| `sipp-scenarios/` | Elisa + SIPp examples | N/A (XML) | **Yes** — primary smoke |
| `docker_open5gs/` | https://github.com/herlesupreeth/docker_open5gs | Docker compose (heavy) | Partial — Kamailio IMS XML/routes; needs Open5GS + Rx/N5 for VoLTE |
| `open5gs/` | https://github.com/open5gs/open5gs | meson (5GC) | **No SIP Gm** — `tests/volte`/`vonr` are Diameter/QoS, not CSCF UAC |
| `free5gc/` | https://github.com/free5gc/free5gc | Go modules | **No** — 5GC only (AMF/SMF/UPF); no CSCF |
| `clearwater-live-test/` | https://github.com/Metaswitch/clearwater-live-test | Ruby/rake + Quaff | Needs Clearwater Bono/Sprout/Ellis |
| `quaff/` | https://github.com/rkday/quaff | Ruby gem | SIP DSL; usable if scripted against Elisa |
| `pyhss/` | https://github.com/nickvsnetworking/pyhss | Python | Diameter Cx HSS — optional peer, not Gm UAC |

`loadtest/third_party` → symlink to this directory (compat).

## Must-run on code change (PCAP mandatory)

```bash
# Fail-closed if Elisa :5060 down (default)
./tools/ims-smoke.sh

# Or:
./loadtest/ims-tools/run-on-change.sh

# Install pre-push gate (chains after Digicom attribution guard)
./tools/install-ims-smoke-hook.sh
```

Report: [`docs/agents/ims-external-test-report.md`](../../docs/agents/ims-external-test-report.md).

## Build SIPp

```bash
env -i HOME="$HOME" PATH=/usr/bin:/bin:/usr/local/bin USER="$USER" bash -lc '
  export CMAKE_ROOT=/usr/share/cmake
  cd loadtest/ims-tools/sipp && rm -rf build && mkdir build && cd build
  cmake .. -DCMAKE_BUILD_TYPE=Release -DUSE_PCAP=OFF -DUSE_SCTP=OFF
  make -j"$(nproc)" && ./sipp -v
'
```

Docker fallback: `docker run --rm ctaloi/sipp:latest -v`
