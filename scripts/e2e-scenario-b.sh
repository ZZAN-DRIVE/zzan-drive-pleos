#!/usr/bin/env bash
# End-to-end check of 시나리오 B with REAL vehicle data on the Pleos emulator (debug build):
#   level 2 · 3,000원 → battery 25 % → "얼마나 충전할래?" → 40 kWh → recommendation (A 회사 앞 vs B 집 가는 길) → approve → arrive at B
#   → +40 kWh charge from the Virtual Console → detection → 2,308원 settled → level 3, "파스타까지 약 5,700원".
# Changes emulator state (battery, charge port/switch, location) and restores it on exit.
# Replaces the app's own savings record with the scenario's starting point.
# Usage: scripts/e2e-scenario-b.sh            (the app must be installed: ./scripts/bootstrap.sh --no-verify)
#        scripts/demo.sh scenario-b plays the same story paced for people.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
source "$(dirname "$0")/lib/scenario.sh"
detect_platform; resolve_sdk_root
require_pleos_emulator
console_forward

step "Console session"
scenario_begin
info "original energy/soc: $SCN_ORIG"

step "Start at level 2 · 3,000원 (짠순이), battery full"
ev_put battery_level 77.4; ev_put soc 100; ev_put charge_port_open false; ev_put charge_switch false
geo 37.41272 127.09468 # near 회사 앞 충전소
adb_s shell am start -W -n "$SCN_APP" >/dev/null
sleep 3
adb_s logcat -c
app_cmd seedB
check "seed" "^SEED level=2 accrual=3000" 10

app_cmd threshold --es zzan.arg 25
step "Battery drops to 25 % (19.35 kWh): the character asks the amount first (the plan, p.5)"
ev_put battery_level 19.35; ev_put soc 25
check "asks the amount" "^ASK fill reason=low" 20
app_cmd fill --es zzan.arg q40
check "recommendation" "^REC .*baseline=ev-a best=ev-b expected=2308 mode=COST_INCLUDED" 20
check "spoken recommendation" "^SAY .*약 2,300원 이득이야" 5

step "Driver approves; arrive at B"
app_cmd approve
check "approved + navigation" "^DECIDE approved=true .*navigation=true" 10
sleep 4
app_front # back to the app after the navigation screen
geo 37.40200 127.10800
sleep 4

step "Charge +40 kWh at B (port open, switch on, 5 kWh steps)"
ev_put charge_port_open true; ev_put charge_switch true
sleep 5
charge_ramp 19.35 40 8 3 77.4
ev_put charge_switch false; ev_put charge_port_open false
check "detected 40 kWh at B" "^DETECT .*q=40\.000 basis=ENERGY .*station=ev-b" 30
check "settled 2,308원 → level 3" "^SETTLE amount=2308 level=3 accrual=1308 remaining=5692" 10
check "level-up line" "^SAY 짜장면 레벨 달성! 파스타까지 약 5,700원 남았어" 10
check "level-up screen" "^SHOW level-up 3" 10

step "Relaunch: the ledger replays without a second credit"
adb_s shell am force-stop "$ZZAN_APP_ID"
adb_s logcat -c
adb_s shell am start -W -n "$SCN_APP" >/dev/null
check "replayed state" "^READY .*state=CharacterState\(level=3, accrualWon=1308\)" 15
sleep 6
if applog | grep -qE "^SETTLE"; then fail "a second SETTLE appeared after relaunch"; else pass "no second credit after relaunch"; fi
if applog | grep -qE "^SAY .*레벨 달성"; then fail "the level-up line was spoken again after relaunch"; else pass "level-up line not repeated after relaunch"; fi

step "Result"
if [ "$SCN_FAILS" = 0 ]; then ok "scenario B passed with real vehicle data"; else die "$SCN_FAILS check(s) failed"; fi
