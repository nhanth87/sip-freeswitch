#!/usr/bin/env bash
# Build ra-freeswitch + Elisa (fast-jar). JDK 25 only (mise zulu-25).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    echo "${ver}" | grep -qE 'version "25' && return 0
    echo "warn: ignoring non-JDK-25 JAVA_HOME=${JAVA_HOME} (${ver})" >&2
  fi
  if command -v mise >/dev/null 2>&1; then
    cand="$(mise where java 2>/dev/null || true)"
    if [[ -n "${cand}" && -x "${cand}/bin/java" ]]; then export JAVA_HOME="${cand}"; return 0; fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17" \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    [[ -x "${cand}/bin/java" ]] && { export JAVA_HOME="${cand}"; return 0; }
  done
  return 1
}

if ! resolve_java25; then
  echo "error: JDK 25 required (mise zulu-25). Do NOT use java-8." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"

cd "${ROOT}/ra-freeswitch"
mvn -q -DskipTests=false test
mvn -q -DskipTests install
cd "${ROOT}/elisa"
mvn -q -DskipTests=false test
mvn -q -Dquarkus.package.jar.type=fast-jar package
echo "OK: ra-freeswitch + Elisa built (fast-jar)"
