#!/usr/bin/env bash
# Install IMS SIP on-change smoke into sip-freeswitch git pre-push (chains after
# Digicom attribution guard — does NOT replace it).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS="$(git -C "$ROOT" rev-parse --git-path hooks)"
PRE_PUSH="$HOOKS/pre-push"
MARKER="DIGICOM-ET-ELISA-IMS-SMOKE-GATE v1"
FRAG="$ROOT/tools/hooks/ims-smoke-pre-push.sh"

mkdir -p "$HOOKS" "$ROOT/tools/hooks"
chmod +x "$ROOT/loadtest/ims-tools/run-on-change.sh" "$ROOT/tools/ims-smoke.sh" "$FRAG" 2>/dev/null || true

if [[ ! -f "$FRAG" ]]; then
  echo "missing $FRAG" >&2
  exit 1
fi

if [[ -f "$PRE_PUSH" ]] && grep -qF "$MARKER" "$PRE_PUSH"; then
  echo "already installed: $PRE_PUSH ($MARKER)"
  exit 0
fi

if [[ ! -f "$PRE_PUSH" ]]; then
  cat > "$PRE_PUSH" <<'EOF'
#!/bin/sh
# empty base — Digicom attribution guard should be present in this repo
exit 0
EOF
  chmod +x "$PRE_PUSH"
fi

# Insert before final successful exit 0 of attribution guard, or append.
tmp="$(mktemp)"
awk -v frag="$FRAG" -v marker="$MARKER" '
  BEGIN { inserted=0 }
  /^exit 0$/ && !inserted {
    print ""
    print "# ---- " marker " (do not remove) ----"
    print "if [ -x \"" frag "\" ]; then"
    print "  \"" frag "\" || exit $?"
    print "fi"
    print "# ---- end " marker " ----"
    print ""
    inserted=1
  }
  { print }
  END {
    if (!inserted) {
      print ""
      print "# ---- " marker " (do not remove) ----"
      print "if [ -x \"" frag "\" ]; then"
      print "  \"" frag "\" || exit $?"
      print "fi"
      print "# ---- end " marker " ----"
    }
  }
' "$PRE_PUSH" > "$tmp"
mv "$tmp" "$PRE_PUSH"
chmod +x "$PRE_PUSH"
echo "installed IMS smoke gate into $PRE_PUSH"
echo "runs when push includes changes under elisa/ ra-freeswitch/ loadtest/sipp app/html/admin ra-sip paths"
echo "override: IMS_SMOKE_ALLOW_SKIP=1 (not for SIP claims)  IMS_SMOKE_FORCE=1 (always run)"
