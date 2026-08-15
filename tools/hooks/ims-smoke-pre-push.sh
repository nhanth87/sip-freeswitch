#!/usr/bin/env bash
# DIGICOM-ET-ELISA-IMS-SMOKE-GATE v1
# Called from git pre-push after attribution guard. Fail-closed on SIP path changes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SMOKE="$ROOT/tools/ims-smoke.sh"

# When not in a push stdin context, allow manual run.
changed=""
if [[ -n "${IMS_SMOKE_FORCE:-}" ]]; then
  changed="force"
else
  # pre-push feeds refs on stdin — consume and compute range vs upstream
  ZERO=0000000000000000000000000000000000000000
  while read -r local_ref local_sha remote_ref remote_sha || [[ -n "${local_ref:-}" ]]; do
    [[ -z "${local_sha:-}" ]] && break
    [[ "$local_sha" = "$ZERO" ]] && continue
    if [[ "$remote_sha" = "$ZERO" ]]; then
      range="$local_sha"
      list=$(git -C "$ROOT" rev-list "$local_sha" --not --glob='refs/remotes/*/*' 2>/dev/null || true)
    else
      range="${remote_sha}..${local_sha}"
      list=$(git -C "$ROOT" rev-list "$range" 2>/dev/null || true)
    fi
    if [[ -n "$list" ]]; then
      files=$(git -C "$ROOT" diff-tree --no-commit-id --name-only -r $list 2>/dev/null || true)
    else
      files=$(git -C "$ROOT" diff --name-only "$range" 2>/dev/null || true)
    fi
    changed="$changed"$'\n'"$files"
  done
fi

# Also cover non-push invocation (empty stdin): check against upstream or HEAD~1
if [[ -z "${changed//[$'\n']/}" ]]; then
  if [[ -n "${IMS_SMOKE_FORCE:-}" ]]; then
    :
  else
    # No refs from stdin (install test / direct call) — check working tree SIP paths
    files=$(git -C "$ROOT" diff --name-only HEAD 2>/dev/null || true)
    files2=$(git -C "$ROOT" diff --name-only --cached 2>/dev/null || true)
    changed="$files"$'\n'"$files2"
  fi
fi

need=0
if [[ -n "${IMS_SMOKE_FORCE:-}" ]]; then
  need=1
elif printf '%s\n' "$changed" | grep -qE \
  '^(elisa/|loadtest/sipp/|loadtest/ims-tools/sipp-scenarios/|app/html/admin/|docs/agents/ims-|tools/ims-smoke)'; then
  need=1
elif printf '%s\n' "$changed" | grep -qiE 'sip|ims|cscf|pcscf'; then
  # catch SIP-related renames outside the prefixes
  need=1
fi

if [[ "$need" -eq 0 ]]; then
  echo "[ims-smoke] skip — no Elisa SIP/IMS path changes in push range"
  exit 0
fi

echo "[ims-smoke] SIP/IMS paths changed — running PCAP smoke (fail-closed if :5060 down)"
exec "$SMOKE"
