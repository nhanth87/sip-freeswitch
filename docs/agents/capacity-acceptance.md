# Capacity acceptance — 10 000 INVITE/s

## Metric

**CPS** = sustained INVITE rate (call attempts per second) measured at the sip-servlet edge for ≥60s.

Not claimed until this checklist is green on a documented hardware profile.

## Error budget (green)

| Metric | Threshold |
|--------|-----------|
| INVITE CPS | ≥ 10 000 sustained |
| Final failure rate (5xx / timeout) | ≤ 0.1% |
| Setup p99 (INVITE → 180 or 200) | ≤ 200 ms (signaling-only scenario) |
| Process OOM / crash | 0 |

## How to measure

```bash
./tools/build-all.sh
./run.sh   # SIP :5060, admin :18080
./tools/bench-cps.sh   # requires sipp on PATH
```

Scenarios: [`loadtest/sipp/`](../../loadtest/sipp/).

## Phase-0 baseline

Until SIPp green at 10k: record max green CPS in bench output and treat 10k as **target**, not fact.
