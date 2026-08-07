#!/usr/bin/env bash
# Elisa (sip-freeswitch) — dist starter (Quarkus fast-jar layout, OTA/ussdgw parity).
# Never java -jar elisa-app.jar alone; never ship uber-jar. JDK 25 only.
#
# Layout (this dir = APP_HOME):
#   run.sh quarkus-run.jar elisa-app.jar
#   lib/boot/ lib/main/ quarkus/ app/html/ configs/ logs/
#
# Tunables (override before start):
#   SF_HTTP_PORT=8088                 admin HTTP port (also SF port guard)
#   SF_HTML_DIR=$APP_HOME/app/html    disk admin UI (edit without rebuild)
#   SF_LOG_DIR=$APP_HOME/logs         log output (never /tmp)
#   JAVA_OPTS / SF_JAVA_OPTS          extra JVM args (appended)
#   SF_XMX / SF_XMS                   heap (default 2g / 512m)
#   SF_ZGC=0                          disable ZGC (default on)
#   QUARKUS_PROFILE=prod              Quarkus profile (default prod)
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${APP_HOME}"

JAR="${APP_HOME}/quarkus-run.jar"
APP_JAR="${APP_HOME}/elisa-app.jar"
CONFIG_FILE="${APP_HOME}/configs/application.properties"

if [[ -f "${APP_HOME}/elisa.jar" && ! -f "${JAR}" ]]; then
  echo "error: found legacy uber-jar layout but missing quarkus-run.jar" >&2
  echo "  Re-run ./tools/package-dist.sh (fast-jar). Single uber-jar is not supported." >&2
  exit 1
fi
if [[ ! -f "${JAR}" ]]; then
  echo "error: missing ${JAR}" >&2
  exit 1
fi
if [[ ! -f "${APP_JAR}" ]]; then
  echo "error: missing ${APP_JAR} (app classes at APP_HOME root)" >&2
  echo "  Re-run ./tools/package-dist.sh — do not leave the jar under app/." >&2
  exit 1
fi
if [[ ! -d "${APP_HOME}/lib/main" ]]; then
  echo "error: missing ${APP_HOME}/lib/main (incomplete fast-jar dist)" >&2
  exit 1
fi
if find "${APP_HOME}/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
  echo "error: jars must not live under app/ (UI only)." >&2
  find "${APP_HOME}/app" -type f -name '*.jar' >&2 || true
  exit 1
fi
if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "error: missing ${CONFIG_FILE}" >&2
  echo "  Expected configs/application.properties (re-run ./tools/package-dist.sh)." >&2
  exit 1
fi

# ── JDK 25 (mise zulu-25 preferred; refuse non-25) ───────────────────
resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    if echo "${ver}" | grep -qE 'version "25'; then
      return 0
    fi
    echo "warn: ignoring non-JDK-25 JAVA_HOME=${JAVA_HOME} (${ver})" >&2
  fi
  if command -v mise >/dev/null 2>&1; then
    cand="$(mise where java 2>/dev/null || true)"
    if [[ -n "${cand}" && -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      return 0
    fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17" \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25.0.2" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      return 0
    fi
  done
  return 1
}

if ! resolve_java25; then
  echo "error: JDK 25 required (mise zulu-25). Do NOT use java-8." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
JAVA_VER="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
if ! echo "${JAVA_VER}" | grep -qE 'version "25'; then
  echo "error: JAVA_HOME must be JDK 25, got: ${JAVA_VER}" >&2
  exit 1
fi

# ── dirs (never /tmp) ────────────────────────────────────────────────
SF_LOG_DIR="${SF_LOG_DIR:-${APP_HOME}/logs}"
case "${SF_LOG_DIR}" in
  /tmp|/tmp/*) echo "error: SF_LOG_DIR must not be under /tmp (got ${SF_LOG_DIR})" >&2; exit 1 ;;
esac
SF_HTML_DIR="${SF_HTML_DIR:-${APP_HOME}/app/html}"
mkdir -p "${SF_LOG_DIR}"

# ── port guard ───────────────────────────────────────────────────────
PORT="${SF_HTTP_PORT:-8088}"
if ss -tln 2>/dev/null | grep -qE ":${PORT}\\b"; then
  echo "warn: admin port ${PORT} already in use." >&2
  ss -tlnp 2>/dev/null | grep -E ":${PORT}\\b" || true
  exit 1
fi

# ── JVM args (Java 25 + ZGC + virtual threads friendly) ──────────────
XMX="${SF_XMX:-2g}"
XMS="${SF_XMS:-512m}"
JVM_ARGS=("-Xms${XMS}" "-Xmx${XMX}" "-XX:+ExitOnOutOfMemoryError")
if [[ "${SF_ZGC:-1}" != "0" ]]; then
  JVM_ARGS+=("-XX:+UseZGC")
fi
JVM_ARGS+=(
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=${SF_LOG_DIR}/heap.hprof"
  "-Djava.net.preferIPv4Stack=true"
)
extra="${JAVA_OPTS:-${SF_JAVA_OPTS:-}}"
if [[ -n "${extra}" ]]; then
  # shellcheck disable=SC2206
  JVM_ARGS+=(${extra})
fi

PROFILE="${QUARKUS_PROFILE:-prod}"

echo "JAVA_HOME=${JAVA_HOME} (${JAVA_VER})"
echo "Config   ${CONFIG_FILE}"
echo "HTML     ${SF_HTML_DIR}/   (disk admin UI — edit without rebuild)"
echo "Logs     ${SF_LOG_DIR}/elisa.log"
echo "Starting Elisa SIP + FreeSWITCH edge (fast-jar: quarkus-run.jar + elisa-app.jar + lib/)"
echo "  admin    http://localhost:${PORT}/admin/"
echo "  metrics  http://localhost:9090/metrics"
echo "  SIP      udp/tcp ${SF_SIP_PORT:-5060}   trunk → FreeSWITCH; ESL ${SF_ESL_HOST:-localhost}:${SF_ESL_PORT:-8021}"
echo

exec "${JAVA_HOME}/bin/java" \
  "${JVM_ARGS[@]}" \
  "-Dsf.html-dir=${SF_HTML_DIR}" \
  "-Dquarkus.http.port=${PORT}" \
  "-Dquarkus.log.file.enable=true" \
  "-Dquarkus.log.file.path=${SF_LOG_DIR}/elisa.log" \
  "-Dquarkus.config.locations=file:${CONFIG_FILE}" \
  "-Dquarkus.profile=${PROFILE}" \
  -jar "${JAR}" "$@"
