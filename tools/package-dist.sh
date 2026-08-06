#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/elisa"
APP="$ROOT/elisa/target/quarkus-app"
rm -rf "$DIST"
mkdir -p "$DIST/bin" "$DIST/app" "$DIST/configs"
if [[ ! -f "$APP/quarkus-run.jar" ]]; then
  "$ROOT/tools/build-all.sh"
fi
cp -a "$APP/." "$DIST/bin/"
cp -a "$ROOT/app/html" "$DIST/app/html"
cp "$ROOT/elisa/src/main/resources/application.properties" "$DIST/configs/application.properties"
cat > "$DIST/run.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export APP_HOME="$HERE"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$HERE/bin"
exec java -Dsf.html-dir="$HERE/app/html" \
  -Dquarkus.config.locations="file:$HERE/configs/application.properties" \
  -jar quarkus-run.jar "$@"
EOF
chmod +x "$DIST/run.sh"
cat > "$DIST/README.md" <<'EOF'
# Elisa dist (sip-freeswitch)

PRIVATE — nhanth87/sip-freeswitch only.

```bash
./run.sh
# admin http://127.0.0.1:8088/admin/  (dev login admin/admin when sf.admin.dev-login=true)
# SIP udp/tcp 5060
```

Edit HTML under `app/html/` without rebuilding the jar.
EOF
echo "packaged $DIST"
