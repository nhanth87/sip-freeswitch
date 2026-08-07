#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT/ra-freeswitch"
mvn -q -DskipTests=false test
mvn -q -DskipTests install
cd "$ROOT/elisa"
mvn -q -DskipTests=false test
mvn -q -Dquarkus.package.jar.type=fast-jar package
echo "OK: ra-freeswitch + Elisa built"
