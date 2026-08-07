#!/usr/bin/env bash
# Elisa lab launcher (worktree). JDK 25 only. Runs from target/quarkus-app.
# For a shippable tree use ./tools/package-dist.sh then dist/elisa/run.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

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
export TMPDIR="${TMPDIR:-/tmp}"
export APP_HOME="${ROOT}"
export SF_HTML_DIR="${SF_HTML_DIR:-${ROOT}/app/html}"
SF_LOG_DIR="${SF_LOG_DIR:-${ROOT}/logs}"
mkdir -p "${SF_LOG_DIR}"

APP="${ROOT}/elisa/target/quarkus-app"
if [[ ! -f "${APP}/quarkus-run.jar" || "${1:-}" == "--build" ]]; then
  "${ROOT}/tools/build-all.sh"
fi
cd "${APP}"
exec "${JAVA_HOME}/bin/java" \
  -Dsf.html-dir="${SF_HTML_DIR}" \
  -Dquarkus.log.file.enable=true \
  -Dquarkus.log.file.path="${SF_LOG_DIR}/elisa.log" \
  -jar quarkus-run.jar "${@/--build/}"
