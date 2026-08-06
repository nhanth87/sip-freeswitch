#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT/elisa"
mvn -q -DskipTests=false test
mvn -q -Dquarkus.package.jar.type=fast-jar package
echo "OK: Elisa built under $ROOT/elisa/target"
