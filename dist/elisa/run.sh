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
