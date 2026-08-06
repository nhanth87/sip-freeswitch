# Loadtest (SIPp)

Requires [`sipp`](https://github.com/SIPp/sipp) on `PATH`.

```bash
./tools/bench-cps.sh           # ramp 100 → 1k → … toward 10k
./tools/bench-cps.sh 500 30    # fixed 500 CPS for 30s
```

Scenario: [`sipp/uac_invite_bye.xml`](sipp/uac_invite_bye.xml) against edge `127.0.0.1:5060`.

Do **not** claim 10k CPS until [`docs/agents/capacity-acceptance.md`](../docs/agents/capacity-acceptance.md) is green.
