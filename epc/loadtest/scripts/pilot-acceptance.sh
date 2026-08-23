#!/usr/bin/env bash
# Kept for compatibility with docs/agents/epc-p1.md ("pilot-acceptance.sh").
# Delegates to the full acceptance runner. Fail-closed: no results file == fail.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Full wire attach / 10k contexts / 1–2 Gbit/s still requires the operator lab
# (open5gs MME + UERANSIM + real GTP-U). This harness proves the runnable local
# gates only and writes results/<date>-epc.json — never a gigabit claim.
exec "$SCRIPT_DIR/epc-acceptance.sh" "$@"
