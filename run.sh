#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR="${TMPDIR:-/tmp}"

APP="$ROOT/sip-servlet/target/quarkus-app"
if [[ ! -f "$APP/quarkus-run.jar" ]]; then
  "$ROOT/tools/build-all.sh"
fi
cd "$APP"
exec "$JAVA_HOME/bin/java" -jar quarkus-run.jar "$@"
