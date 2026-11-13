#!/usr/bin/env bash
# Verify that the installed app really talks to the Pleos emulator (not just "it launched").
# Usage: scripts/verify-app.sh            (called by scripts/bootstrap.sh)
#
# Changes emulator state: Battery SOC only (original -> test value -> original, restored on exit).
# Doors, gear and speed are never written. Runs only on a local emulator-* serial with a Pleos AVD.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
detect_platform; resolve_sdk_root
mkdir -p "$ZZAN_STATE_DIR"
REPORT="$ZZAN_STATE_DIR/report.json"
LOGFILE="$ZZAN_STATE_DIR/app-logcat.txt"
require_pleos_emulator

ROWS=""      # newline-separated "STATUS|check|detail"
FAILS=0; WARNS=0
record() { ROWS="${ROWS}$1|$2|$3"$'\n'; [ "$1" = FAIL ] && FAILS=$((FAILS + 1)); [ "$1" = WARN ] && WARNS=$((WARNS + 1)); return 0; }

applog() { adb_s logcat -d -v raw -s JjanShell:I 2>/dev/null | tr -d '\r' > "$LOGFILE"; cat "$LOGFILE"; }
count_lines() { applog | grep -cE "$1" || true; }
wait_for_line() { # wait_for_line REGEX TIMEOUT_S [MIN_COUNT] -> 0 when seen
  local t=0 need="${3:-1}"
  while [ $t -lt "$2" ]; do
    [ "$(count_lines "$1")" -ge "$need" ] && return 0
    sleep 1; t=$((t + 1))
  done
  return 1
}
last_line() { applog | grep -E "$1" | tail -1 || true; }   # empty when absent, never fails
last_or() { local r; r="$(last_line "$1")"; printf '%s' "${r:-$2}"; }

# ------------------------------------------------------------------ console
step "Virtual Console $ZZAN_CONSOLE_URL"
console_forward
code="$(console_http_code)"
if [ "$code" = 200 ]; then record PASS "console" "HTTP 200"; else record FAIL "console" "HTTP $code"; fi

ORIG_SOC=""; SOC_URL=""; SESSION=0
restore_soc() {
  if [ -n "$ORIG_SOC" ] && [ -n "$SOC_URL" ] && [ "$SESSION" = 1 ]; then
    console_put "${SOC_URL}${ORIG_SOC}" >/dev/null 2>&1 || warn "Could not restore SOC to $ORIG_SOC. Set it in the console."
  fi
  console_session_stop
}
trap restore_soc EXIT

# Build the SOC write URL from the console's own catalog (feature "ev-battery-soc").
soc_url_from_catalog() {
  local cat i id ep pv fld
  cat="$(console_get /vehicle/catalog)" || return 1
  for i in $(seq 0 200); do
    id="$(printf '%s' "$cat" | json_get "features.$i.id")" || break
    [ -n "$id" ] || break
    [ "$id" = ev-battery-soc ] || continue
    ep="$(printf '%s' "$cat" | json_get "features.$i.write_endpoint")"
    pv="$(printf '%s' "$cat" | json_get "features.$i.control.params.field")"
    fld="$(printf '%s' "$cat" | json_get "features.$i.control.field")"
    [ -n "$ep" ] && [ -n "$pv" ] && [ -n "$fld" ] || return 1
    echo "${ep}?field=${pv}&${fld}="
    return 0
  done
  return 1
}

if [ "$code" = 200 ]; then
  if console_session_start; then
    SESSION=1; record PASS "console session" "heartbeat accepted (emulator_active=true)"
  else
    record FAIL "console session" "emulator_active stayed false; open $ZZAN_CONSOLE_URL in a browser and retry"
  fi
fi

# ------------------------------------------------------------------ launch app
step "Launch $ZZAN_APP_ID diagnostics (self-test: TTS once, STT 5 s)"
adb_s shell am force-stop "$ZZAN_APP_ID" >/dev/null 2>&1 || true
adb_s logcat -c
# Diagnostics screen (debug builds only) keeps the SDK checks separate from the product UI.
adb_s shell am start -W -n "$ZZAN_APP_ID/.diag.DiagnosticsActivity" --ez zzan.selftest true >/dev/null
if wait_for_line '^App: 실행됨' 20; then record PASS "app launch" "$(last_line '^App: 실행됨' | sed 's/^App: //')"
else record FAIL "app launch" "no 'App: 실행됨' log within 20 s"; fi

# ------------------------------------------------------------------ SDK checks
wait_for_line '^Vehicle: .*배터리 [0-9]+%' 30 || true
l="$(last_line '^Vehicle: .*배터리')"
if printf '%s' "$l" | grep -qE '배터리 [0-9]+%'; then record PASS "Vehicle read" "${l#Vehicle: }"
else record FAIL "Vehicle read" "${l:-no battery value}"; fi

if wait_for_line '^Vehicle 배터리 구독: 등록 호출 반환' 15; then
  # SOC round trip: only while parked at 0 km/h.
  if [ "$SESSION" = 1 ] && SOC_URL="$(soc_url_from_catalog)"; then
    st="$(console_get /vehicle/state)"
    speed="$(printf '%s' "$st" | json_get controls.speed_kph)"; speed="${speed%%.*}"
    # gear.actual is null without a session and {value, source, ...} with one.
    gear="$(printf '%s' "$st" | json_get gear.actual.value || true)"
    soc="$(printf '%s' "$st" | json_get ev_battery.state_of_charge)"; soc="${soc%%.*}"
    if [ "${speed:-x}" != 0 ] || { [ -n "$gear" ] && [ "$gear" != Park ]; }; then
      record WARN "SOC round trip" "skipped: vehicle not parked (gear=${gear:-?}, speed=${speed:-?})"
    elif [ -z "$soc" ]; then
      record FAIL "SOC round trip" "console did not report state_of_charge"
    else
      ORIG_SOC="$soc"; target=42; [ "$soc" = 42 ] && target=58
      before="$(count_lines "이벤트 수신: ${target}%")"
      console_put "${SOC_URL}${target}" >/dev/null
      if wait_for_line "이벤트 수신: ${target}%" 20 $((before + 1)); then
        before="$(count_lines "이벤트 수신: ${ORIG_SOC}%")"
        console_put "${SOC_URL}${ORIG_SOC}" >/dev/null
        if wait_for_line "이벤트 수신: ${ORIG_SOC}%" 20 $((before + 1)); then
          record PASS "SOC round trip" "console ${ORIG_SOC}→${target}→${ORIG_SOC}%, app callback received both"
        else
          record FAIL "SOC round trip" "callback for restore (${ORIG_SOC}%) not received"
        fi
      else
        record FAIL "SOC round trip" "console set ${target}% but app callback not received in 20 s"
      fi
    fi
  else
    record WARN "SOC round trip" "skipped: no console session or SOC feature not in /vehicle/catalog"
  fi
else
  record FAIL "SOC subscription" "registerEvBatteryStateOfCharge did not return"
fi

if wait_for_line '^Navi: 서비스 연결 콜백 수신' 20; then
  wait_for_line '^Navi 위치:' 10 || true   # location arrives shortly after the connect callback
  record PASS "NaviHelper" "connected · $(last_or '^Navi 위치:' 'Navi 위치: (no location yet)' | sed 's/^Navi 위치: //')"
else record FAIL "NaviHelper" "$(last_or '^Navi:' 'no connect callback')"; fi

if wait_for_line '^TTS: ON_DEVICE 초기화 성공' 20; then
  if wait_for_line '^TTS 이벤트: onDone' 25; then record PASS "TextToSpeech" "ON_DEVICE init + speak → onDone"
  else record WARN "TextToSpeech" "init OK, synthesis did not finish: $(last_line '^TTS 이벤트:' | sed 's/^TTS 이벤트: //')"; fi
else record FAIL "TextToSpeech" "$(last_or '^TTS:' 'no init callback')"; fi

if wait_for_line '^STT: ON_DEVICE 초기화 성공' 20; then
  if wait_for_line '^STT 이벤트: onReady' 25; then record PASS "SpeechToText" "ON_DEVICE init + request → onReady (no speech input)"
  else record WARN "SpeechToText" "init OK, request did not reach onReady"; fi
else record FAIL "SpeechToText" "$(last_or '^STT:' 'no init callback')"; fi

if wait_for_line '^Location (구독|마지막 위치): -?[0-9]' 20; then
  record PASS "FusedLocation" "$(last_line '^Location (구독|마지막 위치): -?[0-9]' | sed -E 's/^Location [^:]+: //')"
elif applog | grep -q 'isSupported() = false'; then record WARN "FusedLocation" "not supported on this image"
else record FAIL "FusedLocation" "$(last_or '^Location' 'no location')"; fi

if wait_for_line '^UX 제한:' 10; then
  l="$(last_line '^UX 제한:')"
  case "$l" in *"정차"*) record PASS "UX restriction" "${l#UX 제한: }" ;; *) record WARN "UX restriction" "${l#UX 제한: }" ;; esac
else record WARN "UX restriction" "no CarUxRestrictions callback"; fi


restore_soc; trap - EXIT

# ------------------------------------------------------------------ product screen
# The product screen runs the app's own runtime (recommendation, detection, settlement) on real
# vehicle data. Its log tag is ZzanApp; the line "DATA first ..." appears once per start.
step "Launch $ZZAN_APP_ID product screen (runtime on real vehicle data)"
prodlog() { adb_s logcat -d -v raw -s ZzanApp:I 2>/dev/null | tr -d '\r'; }
adb_s shell am force-stop "$ZZAN_APP_ID" >/dev/null 2>&1 || true
adb_s logcat -c
adb_s shell am start -W -n "$ZZAN_APP_ID/.MainActivity" >/dev/null
t=0
while [ $t -lt 20 ] && ! prodlog | grep -qE '^DATA first .*origin=PLEOS_SDK'; do sleep 1; t=$((t + 1)); done
ready="$(prodlog | grep -E '^READY ' | tail -1 || true)"
data="$(prodlog | grep -E '^DATA first .*origin=PLEOS_SDK' | tail -1 || true)"
if [ -n "$ready" ] && [ -n "$data" ]; then
  record PASS "product runtime" "$(printf '%s' "$ready" | sed -E 's/^READY (mode=[A-Z]+).*state=CharacterState\(level=([0-9]+), accrualWon=([0-9]+)\).*/\1 · level \2 · accrual \3/') · ${data#DATA first }"
elif [ -n "$ready" ]; then
  record FAIL "product runtime" "started, but no vehicle data from the Pleos SDK within 20 s"
else
  record FAIL "product runtime" "no READY log from MainActivity within 20 s"
fi

# ------------------------------------------------------------------ report
step "Result"
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
{
  printf '{\n  "date": "%s",\n  "app": "%s",\n  "serial": "%s",\n  "avd": "%s",\n  "host": "%s-%s",\n  "checks": [\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$ZZAN_APP_ID" "$ZZAN_SERIAL" "$(running_avd_name)" "$HOST_OS" "$HOST_ARCH"
  first=1
  while IFS='|' read -r s c d; do
    [ -n "$s" ] || continue
    [ $first = 1 ] || printf ',\n'; first=0
    printf '    {"status": "%s", "check": "%s", "detail": "%s"}' "$s" "$(json_escape "$c")" "$(json_escape "$d")"
  done <<EOF
$ROWS
EOF
  printf '\n  ],\n  "fail": %d,\n  "warn": %d,\n  "app_log": "%s"\n}\n' "$FAILS" "$WARNS" "$LOGFILE"
} > "$REPORT"

while IFS='|' read -r s c d; do
  [ -n "$s" ] || continue
  case "$s" in PASS) col="$C_G" ;; WARN) col="$C_Y" ;; *) col="$C_R" ;; esac
  printf '    %s%-4s%s  %-18s %s\n' "$col" "$s" "$C_0" "$c" "$d"
done <<EOF
$ROWS
EOF
info "Report: $REPORT"
[ "$FAILS" = 0 ] || exit 1
