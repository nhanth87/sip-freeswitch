#!/usr/bin/env bash
# Fail-closed pilot gate. Do not print PASS/gigabit without a results file.
set -euo pipefail
echo "pilot-acceptance: no results/<date>.json — cannot claim 10k subs or 1–2 Gbit/s" >&2
exit 2
