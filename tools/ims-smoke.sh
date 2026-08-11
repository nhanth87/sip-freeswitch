#!/usr/bin/env bash
# Thin entrypoint — agents/devs must run before claiming Elisa SIP/IMS fixed (lab).
# PCAP probe + SIPp REGISTER against :5060. Fail-closed if Elisa down.
# See docs/agents/ims-external-test-report.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/loadtest/ims-tools/run-on-change.sh" "$@"
