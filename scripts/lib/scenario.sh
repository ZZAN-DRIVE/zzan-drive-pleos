# Shared by scripts/e2e-scenario-b.sh and scripts/demo.sh. Source after lib/common.sh.
# App log checks, app commands (debug build), Virtual Console EV writes, and restoring the emulator.
# shellcheck shell=bash

SCN_APP="$ZZAN_APP_ID/.MainActivity"
SCN_TAG="ZzanApp"   # live runtime; the in-app demo mode logs as ZzanDemo
SCN_FAILS=0
SCN_ORIG=""         # "energy_kwh soc" before the scenario, restored on exit

pass() { printf '    %sPASS%s  %s\n' "$C_G" "$C_0" "$1"; }
fail() { printf '    %sFAIL%s  %s\n' "$C_R" "$C_0" "$1"; SCN_FAILS=$((SCN_FAILS + 1)); }

applog() { adb_s logcat -d -v raw -s "$SCN_TAG:I" 2>/dev/null | tr -d '\r'; }
wait_log() { # wait_log REGEX TIMEOUT_S
  local t=0
  while [ "$t" -lt "$2" ]; do applog | grep -qE "$1" && return 0; sleep 1; t=$((t + 1)); done
  return 1
}
check() { # check NAME REGEX TIMEOUT_S
  if wait_log "$2" "$3"; then pass "$1: $(applog | grep -E "$2" | tail -1 | cut -c1-110)"; else fail "$1 (no log matching: $2)"; fi
}

app_front() { adb_s shell am start -n "$SCN_APP" "$@" >/dev/null 2>&1; }
app_cmd() { local c="$1"; shift; app_front --es zzan.cmd "$c" "$@"; sleep 1; }   # debug builds only; app_cmd fill --es zzan.arg q40

# stt_clip NAME TEXT: say TEXT with this Mac's Korean voice "Flo" into the app's files (files/stt/NAME.wav, 16 kHz mono
# 16-bit) for `app_cmd hearpcm --es zzan.arg NAME`. Made on the host each time and never committed: Apple's system
# voices may not be redistributed. Returns 1 without macOS say/afconvert or that voice.
stt_clip() {
  local name="$1" text="$2" voice dir user
  command -v say >/dev/null 2>&1 && command -v afconvert >/dev/null 2>&1 || return 1
  voice="$(say -v '?' 2>/dev/null | grep -E '^Flo \(.* ko_KR ' | head -1 | sed -E 's/ +ko_KR .*//')"
  [ -n "$voice" ] || return 1
  dir="$ZZAN_ROOT/build/stt"
  mkdir -p "$dir"
  say -v "$voice" -r 175 -o "$dir/$name.aiff" "$text" || return 1
  afconvert -f WAVE -d LEI16@16000 -c 1 "$dir/$name.aiff" "$dir/$name.wav" || return 1
  user="$(adb_s shell am get-current-user 2>/dev/null | tr -d '\r')"
  # Written to a temporary name and renamed, then checked by size: the app must never read a half-written file
  # (a command that arrives while the copy is still being written fails with "not a WAV file").
  adb_s exec-in "run-as $ZZAN_APP_ID --user ${user:-0} sh -c 'mkdir -p files/stt && cat > files/stt/.$name.part && mv files/stt/.$name.part files/stt/$name.wav'" < "$dir/$name.wav"
  local want got i
  want="$(wc -c < "$dir/$name.wav" | tr -d ' ')"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    got="$(adb_s shell run-as "$ZZAN_APP_ID" --user "${user:-0}" stat -c %s "files/stt/$name.wav" 2>/dev/null | tr -d '\r')"
    [ "$got" = "$want" ] && return 0
    sleep 0.5
  done
  return 1
}
geo() { adb_s emu geo fix "$2" "$1" >/dev/null; }       # geo LAT LON
ev_put() { console_put "/vehicle/ev-battery?field=$1&value=$2" >/dev/null; }
# Gear and speed: Drive + speed > 0 makes the car service report "moving" and the
# system covers the app with "주행 중에는 사용할 수 없습니다" (the activity stays resumed underneath).
drive() { console_put "/vehicle/gear?gear=Drive" >/dev/null; console_put "/vehicle/controls?speed=$1" >/dev/null; }   # drive KPH
park() { console_put "/vehicle/controls?speed=0" >/dev/null; console_put "/vehicle/gear?gear=Park" >/dev/null; }
driving_state() { adb_s shell dumpsys car_service --services CarDrivingStateService 2>/dev/null | tr -d '\r' | sed -n 's/.*Current Driving State: //p' | head -1; }  # 0 parked, 1 idling, 2 moving

# charge_ramp FROM_KWH ADD_KWH STEPS STEP_S CAPACITY_KWH: battery_level and SOC up in equal steps.
# The console's SOC is an integer; the app measures the charge from battery_level (Wh).
charge_ramp() {
  local i kwh soc
  for i in $(seq 1 "$3"); do
    kwh="$(awk -v s="$1" -v a="$2" -v n="$3" -v i="$i" 'BEGIN { printf "%.2f", s + a * i / n }')"
    soc="$(awk -v k="$kwh" -v c="$5" 'BEGIN { printf "%.0f", k / c * 100 }')"
    ev_put battery_level "$kwh"; ev_put soc "$soc"
    sleep "$4"
  done
}

# Opens a console session, remembers the battery, and restores everything on exit (even on failure).
scenario_begin() {
  console_session_start || die "Virtual Console session failed. Is the emulator running? (scripts/emulator.sh start)"
  local state gear
  state="$(console_get /vehicle/state)"
  SCN_ORIG="$(printf '%s' "$state" | json_get ev_battery.current_energy_kwh) $(printf '%s' "$state" | json_get ev_battery.state_of_charge | cut -d. -f1)"
  gear="$(printf '%s' "$state" | json_get gear.actual.value || true)"
  [ "$gear" = Park ] || warn "gear is ${gear:-unknown}, not Park: detection only counts a charge while parked"
  warn_location_sharing
  trap scenario_restore EXIT
}

# On the Pleos 2.1 emulator this app received no location updates while another app was bound to the
# Pleos FusedLocation service as well (the charge then settled as NO_LOCATION).
# Warn before a scenario that needs the location to match the station.
warn_location_sharing() {
  local others
  # `|| true`: under `set -euo pipefail` an empty result (no other app) must not end the script.
  others="$(adb_s shell dumpsys activity services ai.pleos.playground.service.location.fused 2>/dev/null | tr -d '\r' |
    sed -n 's/.*Client AppBindRecord{[^ ]* ProcessRecord{[^ ]* [0-9]*:\([^/]*\)\/.*/\1/p' | { grep -vx "$ZZAN_APP_ID" || true; } |
    sort -u | tr '\n' ' ' || true)"
  [ -z "$others" ] || warn "other apps also use Pleos FusedLocation: ${others}— this app may get no location (stop them: adb shell am force-stop --user 10 <package>)"
}

scenario_restore() {
  step "Restore emulator state"
  park 2>/dev/null || true
  if [ -n "$SCN_ORIG" ]; then
    # shellcheck disable=SC2086
    set -- $SCN_ORIG
    ev_put charge_switch false || true
    ev_put charge_port_open false || true
    ev_put battery_level "$1" || true
    ev_put soc "$2" || true
  fi
  adb_s emu geo fix -122.084 37.421998 >/dev/null 2>&1 || true   # the emulator's default position
  console_session_stop
  info "gear (P), speed, battery, charge port and location restored"
}
