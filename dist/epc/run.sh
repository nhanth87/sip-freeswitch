#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
ver="$("${JAVA_BIN}" -version 2>&1 | head -1 || true)"
echo "${ver}" | grep -qE 'version "25' || { echo "error: JDK 25 required (${ver})"; exit 1; }
cd "${HERE}"
exec "${JAVA_BIN}" -jar quarkus-run.jar
