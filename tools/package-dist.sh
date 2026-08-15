#!/usr/bin/env bash
# nhanth87 Elisa — assemble Quarkus fast-jar runtime into worktree dist/ (OTA / ussdgw parity).
# Usage: ./tools/package-dist.sh
#
# Layout (never uber-jar):
#   quarkus-run.jar     thin launcher
#   elisa-app.jar       application classes at APP_HOME root (NOT under app/)
#   app/html/           admin UI ONLY — never put jars in app/
#   lib/boot/ lib/main/ Quarkus bootstrap + third-party + micro-jainslee + ra-freeswitch
#   quarkus/            generated model (dat rewritten for root jar path)
#   run.sh configs/ logs/ README.md
#
# Deploy: scp -r dist/elisa <host>: && ./elisa/run.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="${ROOT}/elisa"
DIST_ROOT="${SF_DIST_DIR:-${DIST_DIR:-${ROOT}/dist/elisa}}"

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
echo "JAVA_HOME=${JAVA_HOME} (${JAVA_VER})"

# Rewrite one DataOutputStream.writeUTF path in quarkus-application.dat
# (Quarkus records app/<jar>; we ship <jar> at APP_HOME root so app/ stays UI-only).
rewrite_quarkus_app_jar_path() {
  local dat="$1" old_rel="$2" new_rel="$3"
  python3 - "${dat}" "${old_rel}" "${new_rel}" <<'PY'
import struct, sys
from pathlib import Path
dat_path, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
data = dat_path.read_bytes()
old_b, new_b = old.encode("utf-8"), new.encode("utf-8")
if len(old_b) > 0xFFFF or len(new_b) > 0xFFFF:
    sys.exit("error: path too long for writeUTF")
old_enc = struct.pack(">H", len(old_b)) + old_b
new_enc = struct.pack(">H", len(new_b)) + new_b
n = data.count(old_enc)
if n != 1:
    sys.exit(f"error: expected exactly 1 writeUTF path {old!r} in {dat_path}, found {n}")
dat_path.write_bytes(data.replace(old_enc, new_enc, 1))
print(f"  rewritten quarkus-application.dat: {old} -> {new}")
PY
}

verify_dist() {
  local dest="$1" missing=0 req
  local app_jar="${dest}/elisa-app.jar"
  for req in \
    "${dest}/quarkus-run.jar" \
    "${dest}/elisa-app.jar" \
    "${dest}/run.sh" \
    "${dest}/README.md" \
    "${dest}/configs/application.properties" \
    "${dest}/lib/main" \
    "${dest}/lib/boot" \
    "${dest}/quarkus" \
    "${dest}/quarkus/quarkus-application.dat" \
    "${dest}/app/html" \
    "${dest}/logs"; do
    if [[ ! -e "${req}" ]]; then
      echo "error: package incomplete — missing ${req}" >&2
      missing=1
    fi
  done
  if [[ ! -x "${dest}/run.sh" ]]; then
    echo "error: ${dest}/run.sh is not executable" >&2
    missing=1
  fi
  # app/ is UI-only — never ship jars there.
  if find "${dest}/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
    echo "error: jars must not live under ${dest}/app/ (UI only). Found:" >&2
    find "${dest}/app" -type f -name '*.jar' >&2 || true
    missing=1
  fi
  local main_count boot_count
  main_count="$(find "${dest}/lib/main" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
  boot_count="$(find "${dest}/lib/boot" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "${main_count}" -lt 30 ]]; then
    echo "error: lib/main has only ${main_count} jars (want >= 30) — dist not self-contained" >&2
    missing=1
  else
    echo "  verified lib/main=${main_count} jars, lib/boot=${boot_count} jars"
  fi
  # App model must point at root jar, not Quarkus default app/<jar>.
  if ! python3 - "${dest}/quarkus/quarkus-application.dat" <<'PY'
import struct, sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()
want = b"elisa-app.jar"
enc = struct.pack(">H", len(want)) + want
if data.count(enc) != 1:
    sys.exit(f"dat writeUTF elisa-app.jar count={data.count(enc)} (want 1)")
PY
  then
    echo "error: quarkus-application.dat does not reference root elisa-app.jar" >&2
    missing=1
  else
    echo "  verified quarkus-application.dat -> elisa-app.jar (not under app/)"
  fi
  # App jar must be Java 25 bytecode (major 69). Fail loudly on wrong JDK.
  local sample major
  if [[ -f "${app_jar}" ]]; then
    sample="$(jar tf "${app_jar}" 2>/dev/null | grep '\.class$' | grep -v 'META-INF/versions/' | head -1 || true)"
    if [[ -n "${sample}" ]]; then
      major="$(javap -verbose -classpath "${app_jar}" "${sample%.class}" 2>/dev/null \
        | awk '/major version:/ {print $3; exit}')"
      if [[ "${major}" != "69" ]]; then
        echo "error: ${app_jar} major version=${major:-unknown} (want 69 = Java 25)." >&2
        missing=1
      else
        echo "  verified $(basename "${app_jar}") major version 69 (Java 25)"
      fi
    fi
  fi
  if [[ "${missing}" -ne 0 ]]; then
    exit 1
  fi
}

echo "Packaging Elisa Quarkus fast-jar (non-uber) -> ${DIST_ROOT}"
# ra-freeswitch lives in micro-jainslee vendor-ras (com.microjainslee:ra-freeswitch).
( cd "${APP_DIR}" && mvn -B -ntp package -Dquarkus.build.skip=false -Dmaven.test.skip=true \
    -Dquarkus.package.jar.type=fast-jar )

QA="${APP_DIR}/target/quarkus-app"
if [[ ! -f "${QA}/quarkus-run.jar" ]]; then
  echo "error: fast-jar layout missing: ${QA}/quarkus-run.jar" >&2
  exit 1
fi
if [[ ! -d "${QA}/lib/main" ]]; then
  echo "error: missing ${QA}/lib/main" >&2
  exit 1
fi

# Fresh code layout; preserve logs/ across re-package.
mkdir -p "${DIST_ROOT}"/{configs,logs,app}
rm -rf "${DIST_ROOT}/lib" "${DIST_ROOT}/quarkus" "${DIST_ROOT}/bin"
rm -f "${DIST_ROOT}/elisa-app.jar" "${DIST_ROOT}/quarkus-run.jar"
find "${DIST_ROOT}/app" -type f -name '*.jar' -delete 2>/dev/null || true

cp -f "${QA}/quarkus-run.jar" "${DIST_ROOT}/"
cp -a "${QA}/lib" "${DIST_ROOT}/"
cp -a "${QA}/quarkus" "${DIST_ROOT}/"

# App jar: Quarkus writes target/quarkus-app/app/<finalName>.jar — move to APP_HOME
# root as elisa-app.jar so dist/app/ stays UI-only. Rewrite quarkus-application.dat.
src_app_jar="$(find "${QA}/app" -maxdepth 1 -type f -name '*.jar' | head -1 || true)"
if [[ -z "${src_app_jar}" ]]; then
  echo "error: no application jar under ${QA}/app" >&2
  exit 1
fi
quarkus_rel="app/$(basename "${src_app_jar}")"
cp -f "${src_app_jar}" "${DIST_ROOT}/elisa-app.jar"
rewrite_quarkus_app_jar_path \
  "${DIST_ROOT}/quarkus/quarkus-application.dat" \
  "${quarkus_rel}" \
  "elisa-app.jar"

# app/ is UI-only — refresh admin UI from repo app/html.
rm -rf "${DIST_ROOT}/app"
mkdir -p "${DIST_ROOT}/app/html"
cp -a "${ROOT}/app/html/." "${DIST_ROOT}/app/html/"

# External config + starter + READMEs.
cp -f "${APP_DIR}/src/main/resources/application.properties" "${DIST_ROOT}/configs/application.properties"
rm -f "${DIST_ROOT}/application.properties"
cp -f "${SCRIPT_DIR}/run-dist.sh" "${DIST_ROOT}/run.sh"
chmod +x "${DIST_ROOT}/run.sh"
cat > "${DIST_ROOT}/README.md" <<'EOF'
# Elisa dist (sip-freeswitch)

PRIVATE — nhanth87/sip-freeswitch only. JDK 25 only.

```bash
./run.sh
# admin   http://127.0.0.1:8088/admin/
# metrics http://127.0.0.1:9090/metrics
# SIP     udp/tcp 5060 → FreeSWITCH trunk; ESL control :8021
```

Layout (fast-jar, OTA/ussdgw parity):
- `run.sh`            start (JDK 25 only)
- `quarkus-run.jar`   thin launcher — start via run.sh, never java -jar the app jar
- `elisa-app.jar`     application classes (APP_HOME root, not under app/)
- `lib/boot/ lib/main/` Quarkus bootstrap + deps (micro-jainslee, ra-freeswitch, …)
- `quarkus/`          generated app model
- `app/html/`         admin UI ONLY — edit HTML/CSS/JS without rebuild (no jars here)
- `configs/`          application.properties (external)
- `logs/`             elisa.log, gc / heap dumps

Deploy: `scp -r dist/elisa <host>:` then `./elisa/run.sh`.
EOF
cat > "${DIST_ROOT}/lib/README.md" <<'EOF'
# lib/ — replaceable runtime jars (fast-jar)

boot/ = Quarkus bootstrap; main/ = third-party + micro-jainslee + ra-freeswitch.
Swap a dep by replacing the jar with the EXACT same filename, then ./run.sh.
Jars are gitignored; this README is tracked.
EOF

verify_dist "${DIST_ROOT}"

echo
echo "Dist ready: ${DIST_ROOT}"
echo "  Layout: quarkus-run.jar + elisa-app.jar + lib/ + app/html/ (OTA/ussdgw parity)"
echo "  Start:  ${DIST_ROOT}/run.sh"
