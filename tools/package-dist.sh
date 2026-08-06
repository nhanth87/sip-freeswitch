#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/sip-freeswitch"
APP="$ROOT/sip-servlet/target/quarkus-app"
rm -rf "$DIST"
mkdir -p "$DIST/bin"
if [[ ! -f "$APP/quarkus-run.jar" ]]; then
  "$ROOT/tools/build-all.sh"
fi
cp -a "$APP/." "$DIST/bin/"
cp "$ROOT/run.sh" "$DIST/run.sh"
chmod +x "$DIST/run.sh"
cat > "$DIST/README.md" <<'EOF'
# sip-freeswitch dist

```bash
./run.sh
# admin http://127.0.0.1:18080/admin/
# SIP udp/tcp 5060
```

PRIVATE — nhanth87/sip-freeswitch only.
EOF
echo "packaged $DIST"
