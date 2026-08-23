#!/usr/bin/env bash
# Non-interactive EPC acceptance gate. Fails closed — no PASS without evidence.
#
# Gates:
#   (a) JDK 25 present
#   (b) `mvn -B -ntp -pl epc-host -am test` from epc/
#   (c) `./tools/package-dist-epc.sh` from repo root; record dist/epc artifact mtimes
#   (d) OPTIONAL wire peer evidence (lab-only):
#       EPC_MME_HOST[/EPC_MME_PORT]            -> GTPv2-C Echo Request -> Echo Response
#       EPC_DIAMETER_HOST[/EPC_DIAMETER_PORT]  -> Diameter CER -> CEA (Result-Code 2001)
#   (e) write results/<date>-epc.json
#
# Green mvn test is NOT a live EPC. This script never claims attach/gigabit/HA/VoLTE.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
EPC="$ROOT/epc"
RESULTS_DIR="${EPC_RESULTS_DIR:-$SCRIPT_DIR/../results}"
LOG_DIR="$RESULTS_DIR/logs"
DATE="$(date +%F)"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_FILE="${EPC_RESULT_FILE:-$RESULTS_DIR/$DATE-epc.json}"
mkdir -p "$RESULTS_DIR" "$LOG_DIR"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
bool() { [[ "$1" == "true" ]] && echo true || echo false; }

# --- (a) JDK 25 --------------------------------------------------------------
resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    if echo "$ver" | grep -qE 'version "25'; then JDK_VER="$ver"; return 0; fi
  fi
  if command -v mise >/dev/null 2>&1; then
    cand="$(mise where java 2>/dev/null || true)"
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then
      ver="$("$cand/bin/java" -version 2>&1 | head -1 || true)"
      if echo "$ver" | grep -qE 'version "25'; then export JAVA_HOME="$cand"; JDK_VER="$ver"; return 0; fi
    fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25"; do
    if [[ -x "$cand/bin/java" ]]; then export JAVA_HOME="$cand"; JDK_VER="$("$cand/bin/java" -version 2>&1 | head -1)"; return 0; fi
  done
  return 1
}

JDK_OK=false
JDK_VER="$(java -version 2>&1 | head -1 || echo 'no java in PATH')"
if resolve_java25; then
  JDK_OK=true
  export PATH="$JAVA_HOME/bin:$PATH"
fi
JDK_VER="${JDK_VER:-$(java -version 2>&1 | head -1 || echo 'no java')}"

# --- (b) mvn test ------------------------------------------------------------
MVN_CMD="cd $EPC && mvn -B -ntp -pl epc-host -am test"
MVN_LOG="$LOG_DIR/mvn-test-$STAMP.log"
( cd "$EPC" && mvn -B -ntp -pl epc-host -am test ) >"$MVN_LOG" 2>&1
MVN_RC=$?
MVN_OK=$([[ $MVN_RC -eq 0 ]] && echo true || echo false)
MVN_SUMMARY="$(grep -E 'Tests run:|BUILD (SUCCESS|FAILURE)' "$MVN_LOG" | tail -6 | paste -sd'; ' - || true)"

# --- (c) package-dist-epc ----------------------------------------------------
PKG_CMD="cd $ROOT && ./tools/package-dist-epc.sh"
PKG_LOG="$LOG_DIR/package-dist-epc-$STAMP.log"
( cd "$ROOT" && ./tools/package-dist-epc.sh ) >"$PKG_LOG" 2>&1
PKG_RC=$?
PKG_OK=$([[ $PKG_RC -eq 0 ]] && echo true || echo false)

DIST_JAR="$ROOT/dist/epc/quarkus-run.jar"
DIST_RUN="$ROOT/dist/epc/run.sh"
JAR_MTIME=""; RUN_MTIME=""
[[ -f "$DIST_JAR" ]] && JAR_MTIME="$(stat -c '%y' "$DIST_JAR" 2>/dev/null || stat -f '%Sm' "$DIST_JAR" 2>/dev/null || echo 'n/a')"
[[ -f "$DIST_RUN" ]] && RUN_MTIME="$(stat -c '%y' "$DIST_RUN" 2>/dev/null || stat -f '%Sm' "$DIST_RUN" 2>/dev/null || echo 'n/a')"
# --- (d) optional wire peer evidence (lab-only) ------------------------------
DIA_STATUS="skip"; DIA_EV=""
GTP_STATUS="skip"; GTP_EV=""

DIA_HOST="${EPC_DIAMETER_HOST:-}"
DIA_PORT="${EPC_DIAMETER_PORT:-3868}"
if [[ -n "$DIA_HOST" ]]; then
  DIA_LOG="$LOG_DIR/diameter-cea-$STAMP.log"
  DIAMETER_HOST="$DIA_HOST" DIAMETER_PORT="$DIA_PORT" "$SCRIPT_DIR/diameter-cea-smoke.sh" >"$DIA_LOG" 2>&1
  RC=$?
  [[ $RC -eq 0 ]] && DIA_STATUS="pass" || DIA_STATUS="fail"
  DIA_EV="$(tail -1 "$DIA_LOG" || true)"
fi

MME_HOST="${EPC_MME_HOST:-}"
MME_PORT="${EPC_MME_PORT:-2123}"
if [[ -n "$MME_HOST" ]]; then
  GTP_LOG="$LOG_DIR/gtp-echo-$STAMP.log"
  GTP_ECHO_HOST="$MME_HOST" GTP_ECHO_PORT="$MME_PORT" "$SCRIPT_DIR/gtp-echo-smoke.sh" >"$GTP_LOG" 2>&1
  RC=$?
  [[ $RC -eq 0 ]] && GTP_STATUS="pass" || GTP_STATUS="fail"
  GTP_EV="$(tail -1 "$GTP_LOG" || true)"
fi

# --- (e2) wire-procedure gates (peer truth; OPEN unless tshark evidence supplied) ---
# Each procedure is the ANSWER-side evidence on the wire. No capture / no peer => OPEN
# (honest), never PASS. Supply EPC_PCAP_EVIDENCE=/path/to/capture.pcap to evaluate.
WIRE_EVIDENCE_PCAP="${EPC_PCAP_EVIDENCE:-}"
PROC_SPEC=$(cat <<'SPEC'
s6a_air_aia^S6a AIR->AIA (Auth-Info-Answer)^diameter.app_id==16777251 && diameter.cmd.code==318 && diameter.flags.request==0^wire capture vs open5gs MME
s6a_ulr_ula^S6a ULR->ULA (Update-Location-Answer)^diameter.app_id==16777251 && diameter.cmd.code==316 && diameter.flags.request==0^wire capture vs open5gs MME
cx_mar_maa^Cx MAR->MAA (Multimedia-Auth-Answer)^diameter.app_id==16777216 && diameter.cmd.code==303 && diameter.flags.request==0^wire capture vs Elisa S-CSCF
gx_ccr_cca^Gx CCR->CCA (Credit-Control-Answer)^diameter.app_id==16777238 && diameter.cmd.code==272 && diameter.flags.request==0^PCRF Gx wire seam (Phase C)
s6a_ula_subscription^S6a ULA carries Subscription-Data incl. APN-Configuration-Profile (1429)^diameter.app_id==16777251 && diameter.cmd.code==316 && diameter.flags.request==0 && diameter.avp.code==1429^Phase A (epc_apn_profile) — open5gs MME rejects ULA without 1429
pfcp_assoc^PFCP Association/Heartbeat Response^pfcp.msg_type==AssociationSetupResponse || pfcp.msg_type==HeartbeatResponse^ra-pfcp + sgwu/pgwu are alpine placeholders
s5c_create^GTPv2-C S5/S11 Create Session (Req 32 / Resp 33)^gtpv2 && (gtpv2.msg_type==32 || gtpv2.msg_type==33)^EPC_S5C_WIRE=false today
SPEC
)

mk_proc_json() {
  local name="$1" desc="$2" filter="$3" blocked="$4" ok=false status="OPEN" ev="no peer / no capture (pcap path or tshark evidence missing)"
  local frames=0
  if [[ -n "$WIRE_EVIDENCE_PCAP" && -f "$WIRE_EVIDENCE_PCAP" ]]; then
    frames="$(tshark -r "$WIRE_EVIDENCE_PCAP" -Y "$filter" 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "$frames" -gt 0 ]]; then
      ok=true; status="pass"; ev="pcap:$WIRE_EVIDENCE_PCAP frames=$frames ($desc)"
    else
      status="fail"; ev="pcap:$WIRE_EVIDENCE_PCAP frames=0 ($desc)"
    fi
  fi
  printf '{"ok":%s,"status":"%s","evidence":"%s","blocked_by":"%s"}' \
    "$ok" "$status" "$(json_escape "$ev")" "$(json_escape "$blocked")"
}

WIRE_PROCS_JSON="{"
sep=""
while IFS='^' read -r name desc filter blocked; do
  [[ -n "$name" ]] || continue
  WIRE_PROCS_JSON+="$sep\"$name\":$(mk_proc_json "$name" "$desc" "$filter" "$blocked")"
  sep=","
done <<< "$PROC_SPEC"
WIRE_PROCS_JSON+="}"

# attach — full E2E (S6a AIR + GTPv2 Create Session + PFCP) requires 3 planes in one capture.
ATTACH_OK=false; ATTACH_STATUS="OPEN"
ATTACH_EV="no peer / no capture (needs operator-lab eNB + open5gs MME + UERANSIM UE)"
if [[ -n "$WIRE_EVIDENCE_PCAP" && -f "$WIRE_EVIDENCE_PCAP" ]]; then
  air="$(tshark -r "$WIRE_EVIDENCE_PCAP" -Y 'diameter.app_id==16777251 && diameter.cmd.code==318 && diameter.flags.request==1' 2>/dev/null | wc -l | tr -d ' ')"
  cs="$(tshark -r "$WIRE_EVIDENCE_PCAP" -Y 'gtpv2 && gtpv2.msg_type==32' 2>/dev/null | wc -l | tr -d ' ')"
  pf="$(tshark -r "$WIRE_EVIDENCE_PCAP" -Y 'pfcp' 2>/dev/null | wc -l | tr -d ' ')"
  ATTACH_EV="pcap:$WIRE_EVIDENCE_PCAP s6a_air=$air gtpv2_create_session=$cs pfcp=$pf"
  if [[ "$air" -gt 0 && "$cs" -gt 0 && "$pf" -gt 0 ]]; then
    ATTACH_OK=true; ATTACH_STATUS="pass"
  else
    ATTACH_STATUS="fail"
  fi
fi
ATTACH_JSON="$(printf '{"ok":%s,"status":"%s","evidence":"%s","detail":"2001 attach (S6a AIR/AIA+ULR/ULA + S11 Create Session + PFCP Session Establishment)","blocked_by":"needs eNB + UERANSIM UE (operator lab)"}' "$ATTACH_OK" "$ATTACH_STATUS" "$(json_escape "$ATTACH_EV")")"

P4_ATTACH="not-proven (requires operator lab eNB+MME: open5gs MME + UERANSIM)"
[[ "$ATTACH_STATUS" == "pass" ]] && P4_ATTACH="pass (wire evidence present in capture)"
[[ "$ATTACH_STATUS" == "fail" ]] && P4_ATTACH="fail (capture present but incomplete attach)"
# --- (e) results json --------------------------------------------------------
REQUIRED_OK=$([[ "$JDK_OK" == "true" && "$MVN_OK" == "true" && "$PKG_OK" == "true" ]] && echo true || echo false)
WIRE_PROOF=$([[ "$DIA_STATUS" == "pass" || "$GTP_STATUS" == "pass" ]] && echo true || echo false)
WIRE_FAIL=$([[ "$DIA_STATUS" == "fail" || "$GTP_STATUS" == "fail" ]] && echo true || echo false)
OVERALL="pass"
[[ "$REQUIRED_OK" == "true" && "$WIRE_FAIL" == "false" ]] || OVERALL="fail"

cat >"$RESULT_FILE" <<JSON
{
  "date": "$DATE",
  "stamp": "$STAMP",
  "generated_by": "epc/loadtest/scripts/epc-acceptance.sh",
  "gates": {
    "jdk25": {
      "pass": $(bool "$JDK_OK"),
      "version": "$(json_escape "$JDK_VER")"
    },
    "mvn_test": {
      "pass": $(bool "$MVN_OK"),
      "command": "$(json_escape "$MVN_CMD")",
      "exit": $MVN_RC,
      "summary": "$(json_escape "$MVN_SUMMARY")"
    },
    "package_dist": {
      "pass": $(bool "$PKG_OK"),
      "command": "$(json_escape "$PKG_CMD")",
      "exit": $PKG_RC,
      "dist_jar": "$DIST_JAR",
      "dist_jar_mtime": "$(json_escape "$JAR_MTIME")",
      "dist_run_mtime": "$(json_escape "$RUN_MTIME")"
    },
    "wire_diameter_cea": {
      "status": "$DIA_STATUS",
      "host": "$(json_escape "$DIA_HOST")",
      "port": "$DIA_PORT",
      "evidence": "$(json_escape "$DIA_EV")"
    },
    "wire_gtp_echo": {
      "status": "$GTP_STATUS",
      "host": "$(json_escape "$MME_HOST")",
      "port": "$MME_PORT",
      "evidence": "$(json_escape "$GTP_EV")"
    }
  },
  "wire_proof": $(bool "$WIRE_PROOF"),
  "wire_procedures": $WIRE_PROCS_JSON,
  "attach": $ATTACH_JSON,
  "p4_attach": "$(json_escape "$P4_ATTACH")",
  "blocked_by": {
    "s6a_air_aia": "wire capture vs open5gs MME",
    "s6a_ulr_ula": "wire capture vs open5gs MME",
    "cx_mar_maa": "wire capture vs Elisa S-CSCF",
    "gx_ccr_cca": "PCRF Gx wire seam (Phase C)",
    "pfcp_assoc": "sgwu/pgwu alpine placeholders",
    "s5c_create": "EPC_S5C_WIRE=false today",
    "attach": "needs eNB + UERANSIM UE (operator lab)"
  },
  "overall": "$OVERALL"
}
JSON

cat <<EOF

== EPC acceptance summary ==
JDK 25:        $( [[ "$JDK_OK" == "true" ]] && echo PASS || echo FAIL )
  $JDK_VER
mvn test:      $( [[ "$MVN_OK" == "true" ]] && echo PASS || echo FAIL )   (exit $MVN_RC)
package-dist:  $( [[ "$PKG_OK" == "true" ]] && echo PASS || echo FAIL )   (exit $PKG_RC)
diameter CEA:  $DIA_STATUS
gtp echo:      $GTP_STATUS
wire_proof:    $( [[ "$WIRE_PROOF" == "true" ]] && echo true || echo false )
overall:       $OVERALL
results:       $RESULT_FILE

NOTE: green mvn test / package-dist is NOT a live EPC. wire_proof=false or a
wire gate 'skip' means no peer evidence was gathered; attach/gigabit/HA/VoLTE
are NOT claimed and require the operator lab (open5gs MME + UERANSIM).
EOF

[[ "$OVERALL" == "pass" ]]