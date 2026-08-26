#!/usr/bin/env bash
# Assemble epc-host Quarkus fast-jar into dist/epc/. JDK 25 only.
# Usage: ./tools/package-dist-epc.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
EPC="${ROOT}/epc"
DIST_ROOT="${EPC_DIST_DIR:-${ROOT}/dist/epc}"

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
  echo "error: JDK 25 required (mise zulu-25)." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"

( cd "${EPC}" && mvn -B -ntp -pl epc-host -am package -DskipTests )

QA="${EPC}/epc-host/target/quarkus-app"
if [[ ! -f "${QA}/quarkus-run.jar" ]]; then
  echo "error: fast-jar missing ${QA}/quarkus-run.jar" >&2
  exit 1
fi

mkdir -p "${DIST_ROOT}"/{configs,logs}
rm -rf "${DIST_ROOT}/lib" "${DIST_ROOT}/quarkus"
cp -f "${QA}/quarkus-run.jar" "${DIST_ROOT}/"
cp -a "${QA}/lib" "${DIST_ROOT}/"
cp -a "${QA}/quarkus" "${DIST_ROOT}/"
if [[ -d "${QA}/app" ]]; then
  src_app_jar="$(find "${QA}/app" -maxdepth 1 -type f -name '*.jar' | head -1 || true)"
  if [[ -n "${src_app_jar}" ]]; then
    cp -f "${src_app_jar}" "${DIST_ROOT}/epc-app.jar"
  fi
fi
cp -f "${EPC}/epc-host/src/main/resources/application.properties" \
  "${DIST_ROOT}/configs/application.properties"

cat > "${DIST_ROOT}/run.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
ver="$("${JAVA_BIN}" -version 2>&1 | head -1 || true)"
echo "${ver}" | grep -qE 'version "25' || { echo "error: JDK 25 required (${ver})"; exit 1; }
cd "${HERE}"
# Log4j2-only policy (owner mandate 2026-08-26): JUL + jboss-logging facade
# route INTO Log4j2; jboss-logmanager stays the silent Quarkus structural jar.
exec "${JAVA_BIN}" \
  "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager" \
  "-Dorg.jboss.logging.provider=log4j2" \
  -jar quarkus-run.jar
EOF
chmod +x "${DIST_ROOT}/run.sh"

cat > "${DIST_ROOT}/README.md" <<'EOF'
# EPC dist (sip-freeswitch/epc)

PRIVATE — nhanth87/sip-freeswitch. JDK 25. Control plane only — no Java GTP-U.

```bash
EPC_ROLE_HSS=true EPC_ROLE_SGWC=false EPC_ROLE_PGWC=false ./run.sh
# status http://127.0.0.1:8099/epc/status
```

`live` on /epc/status is peer evidence (CEA / GTP Echo / PFCP Association). LISTEN is not UP.
EOF

echo "Dist ready: ${DIST_ROOT}"
