# Loadtest (SIPp)

Requires SIPp on `PATH` (build [`ims-tools/sipp`](../../loadtest/ims-tools/sipp) or install).

```bash
./tools/bench-cps.sh           # ramp 100 → 1k → … toward 10k
./tools/bench-cps.sh 500 30    # fixed 500 CPS for 30s
```

Scenario: [`sipp/uac_invite_bye.xml`](../../loadtest/sipp/uac_invite_bye.xml) against edge `127.0.0.1:5060`.

## IMS tools + on-change PCAP smoke

Canonical clones: [`ims-tools/`](../../loadtest/ims-tools/) — Open5GS, free5gc, SIPp, Clearwater, pyHSS, scenarios.

```bash
./tools/ims-smoke.sh                 # REGISTER + tcpdump/tshark (fail-closed if Elisa down)
./tools/install-ims-smoke-hook.sh    # chain into git pre-push
```

See [`docs/agents/ims-external-test-report.md`](../agents/ims-external-test-report.md).

## N–N mid-call kill (fail-closed)

After `--profile services --profile nn` is up:

```bash
./loadtest/scripts/nn-midcall-kill.sh
```

INVITE 200 → `docker stop scscf` → BYE 200 must be on the pcap. Not Chronos / media remap. Detail: [`docs/agents/cmp-recovery.md`](../agents/cmp-recovery.md).

Do **not** claim 10k CPS until [`docs/agents/capacity-acceptance.md`](../agents/capacity-acceptance.md) is green.
