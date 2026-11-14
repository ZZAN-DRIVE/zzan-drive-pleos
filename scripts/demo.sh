#!/usr/bin/env bash
# Plays the plan's scenarios on the Pleos emulator, paced for people: a live demo or a screen recording.
#
#   scripts/demo.sh scenario-b      # 기획서 시나리오 B, 실제 차량 데이터: [충전소 찾기] → 현재 위치 → 40 kWh
#                                   #   → 도로 5 km 밖 1곳 제외 → B가 2,323원 이득 → 승인 → B에서 +40 kWh → 레벨 3
#   scripts/demo.sh scenario-b-far  # 저장한 곳(본가) 기준: 5 km 안에 없음 → 가장 가까운 1곳만 안내(비교·적립 없음)
#   scripts/demo.sh scenario-b-skip # 양을 건너뜀 → 가격 없이 가까운 곳 안내 → 거기서 충전해도 적립 없음, 다음 추천은 다시 열림
#   scripts/demo.sh scenario-pass   # 기회비용: B를 승인하고 충전 없이 지나침 → 정차 후 "77원 뺐어" → 다음 추천은 다시 열림
#   scripts/demo.sh scenario-b-auto # 배터리 25%(정차): 앱이 먼저 넣을 양을 묻고 → 40 kWh → 추천 → 승인 → +40 kWh → 2,323원
#   scripts/demo.sh scenario-a      # 시연 데이터(Mock): 강릉까지 100 km, 연료 10% → 넣을 양 30 L → 목적지까지 못 감
#                                   #   → 주유소를 들르는 경로 3곳 비교(총 지출) → C가 676원 덜 듦 → 승인 → 30 L → 레벨 1
#   scripts/demo.sh scenario-drive  # 실제 차량 데이터, 음성 대화: 주행 중 배터리 25% → 화면은 가려진 채 "얼마나 충전할래?"
#                                   #   → (인식 결과) 40 kWh → 양을 되말하며 추천 → 녹음된 "응"을 온디바이스 STT로 인식 → 승인
#   scripts/demo.sh scenario-drive-quiet # 음성 답 끔: 주행 중엔 한 줄만 말하고 정차하면 넣을 양을 묻는다
#   scripts/demo.sh scenario-habit  # 시연 데이터(Mock): 공회전 8분, 급출발·급가속 2건, 과속 6.25 km → 주행 끝에 377원을 한 번 차감
#                                   #   → 레벨 적립액은 300원까지만 줄고 누적 1,500원(레벨 달성 금액)은 그대로
#   scripts/demo.sh scenario-levels # 레벨 0 빈 지갑부터 10 킹크랩까지 레벨업 화면과 음성을 차례로 (정차 중)
#
# Options:
#   --step          wait for Enter before each scene (live presentation)
#   --tap           wait for the presenter to tap [응, 들를게] instead of approving by command
#   --record FILE   record the emulator screen and the app's voice to FILE (.webm, at most 180 s)
#   --persona NAME  scenario-levels only: zzandori (짠돌이, default) or zzansuni (짠순이)
#
# Needs the debug build (./scripts/bootstrap.sh). scenario-b changes the battery, charge port/switch and
# location through the Virtual Console and restores them on exit; it replaces the app's own savings
# record with the scenario's starting point. Each scene is checked in the app log; exit 1 on a miss.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
source "$(dirname "$0")/lib/scenario.sh"

usage() { sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; }
SCENARIO=""; STEP=0; TAP=0; RECORD=""; PERSONA="zzandori"
while [ $# -gt 0 ]; do
  case "$1" in
    scenario-a|scenario-b|scenario-b-far|scenario-b-skip|scenario-b-auto|scenario-pass|scenario-drive|scenario-drive-quiet|scenario-habit|scenario-levels) SCENARIO="$1" ;;
    --step)   STEP=1 ;;
    --tap)    TAP=1 ;;
    --record) shift; RECORD="${1:?--record needs a file name}" ;;
    --persona) shift; PERSONA="${1:?--persona needs zzandori or zzansuni}" ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "Unknown option: $1" ;;
  esac
  shift
done
[ -n "$SCENARIO" ] || { usage; die "Choose scenario-a, scenario-b, scenario-b-far, scenario-b-skip, scenario-b-auto, scenario-pass, scenario-drive, scenario-drive-quiet, scenario-habit or scenario-levels."; }
case "$PERSONA" in zzandori|zzansuni) ;; *) die "--persona must be zzandori or zzansuni" ;; esac

detect_platform; resolve_sdk_root
require_pleos_emulator
console_forward

# scene NUMBER TITLE CAPTION: prints the scene (captions double as video subtitles), waits with --step.
scene() {
  step "$1. $2"
  info "$3"
  if [ "$STEP" = 1 ]; then printf '    ▶ Enter = 진행 '; read -r _ </dev/tty; fi
}
hold() { sleep "$1"; }   # leave time for the voice and the screen

RECORDING=0
record_start() {
  [ -n "$RECORD" ] || return 0
  case "$RECORD" in /*) ;; *) RECORD="$PWD/$RECORD" ;; esac
  case "$RECORD" in *.webm) ;; *) die "--record needs a .webm file name" ;; esac
  adb_s emu screenrecord start --time-limit 180 "$RECORD" >/dev/null || die "screen recording did not start"
  RECORDING=1
  info "recording → $RECORD"
}
record_stop() {
  [ "$RECORDING" = 1 ] || return 0
  adb_s emu screenrecord stop >/dev/null 2>&1 || true
  RECORDING=0
  info "recording saved: $RECORD"
}

# approve SAY_REGEX: the presenter taps (--tap) or the debug command approves.
approve() {
  if [ "$TAP" = 1 ]; then
    info "화면에서 [응, 들를게]를 누르세요 (2분 기다림)"
    check "approved by tap" "^DECIDE approved=true" 120
  else
    app_cmd approve
    check "approved" "^DECIDE approved=true" 10
  fi
}

# Scenario B (기획서 p.17): parked at 45 %, the driver asks; one question at a time.
# live_begin SOC_PERCENT: Virtual Console session, battery at SOC, app in front, seed level 2 · 3,000원 · 짠순이.
live_begin() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  step "Virtual Console session"
  scenario_begin
  trap 'record_stop; scenario_restore' EXIT
  info "original energy/soc: $SCN_ORIG (restored at the end)"
  local kwh
  kwh="$(awk -v p="$1" 'BEGIN { printf "%.2f", 77.4 * p / 100 }')"
  ev_put battery_level "$kwh"; ev_put soc "$1"; ev_put charge_port_open false; ev_put charge_switch false
  geo 37.41272 127.09468 # near 회사 앞 충전소
  adb_s shell am start -W -n "$SCN_APP" >/dev/null
  sleep 2
  adb_s logcat -c
  app_cmd seedB
  check "start: level 2 · 3,000원 · 짠순이" "^SEED level=2 accrual=3000" 10
  app_cmd cancel
  app_cmd threshold --es zzan.arg 25
  app_cmd voice --es zzan.arg on
}

play_b() {
  live_begin 45
  record_start

  scene 1 "출발 전, 충전소 찾기" "레벨 2 아메리카노 · 적립 3,000원 · 짠순이. 배터리 45% (실제 차량 데이터). 정차 중 [충전소 찾기]"
  app_cmd find
  check "asks the place" "^ASK reference" 10
  check "asked aloud" "^SAY 어디 근처로 찾을래\?" 5
  hold 4

  scene 2 "현재 위치 기준" "저장한 곳 대신 현재 위치를 고른다 → 이제 넣을 양을 묻는다"
  app_cmd ref --es zzan.arg current
  check "asks the amount" "^ASK fill reason=find" 10
  check "asked aloud" "^SAY 얼마나 충전할래\?" 5
  SCN_TAG="ZzanTts" # 짠순이's own Pleos voice, played only with audio focus
  check "짠순이's voice with audio focus" "^focus granted speaker=haeun" 10
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  hold 4

  scene 3 "40 kWh" "도로 5 km 안의 두 곳만 비교(6.2 km 떨어진 C는 제외): 회사 앞(350원/kWh, 1 km)보다 집 가는 길 B(290원/kWh, 2 km)가 약 2,300원 이득"
  app_cmd fill --es zzan.arg q40
  check "5 km rule" "^CAND ref=current in=ev-a,ev-b excluded=ev-far:6.2" 10
  check "recommendation" "^REC .*baseline=ev-a best=ev-b expected=2323 mode=COST_INCLUDED rule=VS_NEAREST qty=40" 10
  check "spoken" "^SAY .*약 2,300원 이득이야" 5
  hold 8

  scene 4 "응, 들를게" "동의는 따로 묻는다. 승인하면 내비에 B 충전소를 넘긴다 (Pleos NaviHelper)"
  approve
  hold 5
  app_front
  geo 37.40200 127.10800 # B 충전소
  hold 3

  scene 5 "B 충전소에서 충전" "충전구 열림 · 충전 켜짐 → 40 kWh 충전 (Virtual Console로 차량 값을 바꾼다)"
  ev_put charge_port_open true; ev_put charge_switch true
  hold 3
  charge_ramp 34.83 40 8 2.5 77.4
  ev_put charge_switch false; ev_put charge_port_open false

  scene 6 "확인된 만큼만 적립" "차량 데이터로 40 kWh 확인 → 2,323원 확정 → 레벨 3 짜장면, 파스타까지 약 5,700원"
  check "detected 40 kWh at B" "^DETECT .*q=40\.000 basis=ENERGY .*station=ev-b" 30
  check "settled 2,323원 → level 3" "^SETTLE amount=2323 level=3 accrual=1323 remaining=5677" 10
  check "level-up line" "^SAY 짜장면 레벨 달성! 파스타까지 약 5,700원 남았어" 10
  check "level-up screen" "^SHOW level-up 3" 10
  check "level-up clip" "^CLIP .*_levelup\.webp" 10
  check "eats 짜장면" "^CLIP .*_lv0?3_[a-z0-9]+_eat(_bolt[0-9]{3})?(_v[0-9]+)?\.webp" 15
  hold 3
  record_stop
}

play_b_far() {
  live_begin 45
  record_start
  scene 1 "본가 기준으로 찾기" "저장한 곳(본가)을 기준으로 고른다. 도로 5 km 안에 충전소가 없다 (예시 데이터)"
  app_cmd find
  check "asks the place" "^ASK reference" 10
  app_cmd ref --es zzan.arg parents
  check "asks the amount" "^ASK fill reason=find" 10
  app_cmd fill --es zzan.arg q40
  check "nothing within 5 km" "^CAND ref=parents in= excluded=ev-p1:6,ev-p2:7.5" 10
  check "nearest one only, no comparison" "^REC .*best=ev-p1 expected=0 mode=NEAREST_ONLY rule=NONE" 10
  check "spoken" "^SAY 5킬로 안엔 없어\. 제일 가까운 읍내 충전소가 6킬로야\. 갈래\?" 5
  hold 6
  app_cmd decline
  check "declined" "^DECIDE approved=false" 10
  record_stop
}

play_b_skip() {
  live_begin 45
  record_start
  scene 1 "양을 건너뜀" "넣을 양을 답하지 않으면 가격을 빼고 가까운 곳만 본다 (기획서 p.5)"
  app_cmd find
  check "asks the place" "^ASK reference" 10
  app_cmd ref --es zzan.arg current
  check "asks the amount" "^ASK fill reason=find" 10
  app_cmd fill --es zzan.arg skip
  check "route only" "^REC .*best=ev-a expected=0 mode=ROUTE_ONLY rule=NONE qty=0" 10
  check "spoken" "^SAY 제일 가까운 회사 앞 충전소가 1킬로야\. 들를래\?" 5
  hold 5

  scene 2 "가까운 곳에서 충전" "승인 → 회사 앞에서 10 kWh 충전 → 가격을 비교하지 않았으니 적립하지 않는다"
  approve
  app_front
  geo 37.41350 127.09600 # 회사 앞 충전소
  hold 2
  ev_put charge_port_open true; ev_put charge_switch true
  hold 2
  charge_ramp 34.83 10 4 2.5 77.4
  ev_put charge_switch false; ev_put charge_port_open false
  check "detected at A" "^DETECT .*station=ev-a" 30
  check "no comparison, no credit" "^SETTLE skipped reason=NO_COMPARISON" 10
  check "said why" "^SAY 가격 비교 없이 안내한 곳이라 안 쌓였어\." 10
  record_stop
}

# Opportunity cost (기획서 p.7·p.14, spec D-28): approved, passed B without charging.
play_pass() {
  live_begin 45
  record_start
  scene 1 "찾기 → 40 kWh → 응" "레벨 2 · 적립 3,000원. 집 가는 길 B 충전소를 추천받고 들르기로 한다"
  app_cmd find
  check "asks the place" "^ASK reference" 10
  app_cmd ref --es zzan.arg current
  check "asks the amount" "^ASK fill reason=find" 10
  app_cmd fill --es zzan.arg q40
  check "recommendation" "^REC .*best=ev-b expected=2323 mode=COST_INCLUDED rule=VS_NEAREST" 10
  approve
  hold 3
  app_front

  scene 2 "B를 그냥 지나침" "주행 중 B 충전소 300 m 안으로 들어갔다가, 충전 없이 500 m 밖으로 나간다"
  drive 30
  hold 2
  geo 37.40200 127.10800 # at B
  hold 3
  geo 37.40800 127.10800 # ≈ 667 m past B
  check "passed B" "^PASS station=ev-b rec=.* amount=77" 15
  check "deducted once: min(2,323, 77)" "^OPP amount=77 applied=77 level=2 accrual=2923" 5
  if applog | grep -qE "^SAY .*지나와서"; then fail "spoke while driving"; else pass "nothing said while driving"; fi

  scene 3 "정차 후 한 줄" "정차하면 짠순이가 한 줄로 알려 준다. 레벨은 그대로"
  park
  check "told after stopping" "^SAY 집 가는 길 B 충전소를 그냥 지나와서 77원 뺐어\." 15
  hold 4

  scene 4 "다음 추천은 다시 열림" "기회비용으로 이 추천은 끝났다. 배터리가 기준(25%)에 닿으면 다시 묻는다"
  ev_put battery_level 19.35; ev_put soc 25
  check "asks again" "^ASK fill reason=low" 20
  app_cmd cancel
  record_stop
}

play_b_auto() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  step "Virtual Console session"
  scenario_begin
  trap 'record_stop; scenario_restore' EXIT
  info "original energy/soc: $SCN_ORIG (restored at the end)"
  ev_put battery_level 77.4; ev_put soc 100; ev_put charge_port_open false; ev_put charge_switch false
  geo 37.41272 127.09468 # near 회사 앞 충전소
  adb_s shell am start -W -n "$SCN_APP" >/dev/null
  sleep 2
  adb_s logcat -c
  app_cmd seedB
  check "start: level 2 · 3,000원 · 짠순이" "^SEED level=2 accrual=3000" 10
  record_start

  scene 1 "출발 전" "레벨 2 아메리카노 · 적립 3,000원 · 짠순이. 회사 앞, 배터리 100% (실제 차량 데이터)"
  hold 4

  scene 2 "배터리 25%" "배터리가 알림 기준(25%)에 닿으면 짠순이가 먼저 넣을 양을 묻는다 (정차 중이라 화면 카드로 답한다)"
  app_cmd threshold --es zzan.arg 25
  ev_put battery_level 19.35; ev_put soc 25
  check "low level" "^LOW level=25% threshold=25 parked=true" 20
  check "asks the amount" "^ASK fill reason=low" 10
  app_cmd fill --es zzan.arg q40
  check "recommendation" "^REC .*reason=low baseline=ev-a best=ev-b expected=2323 mode=COST_INCLUDED rule=VS_NEAREST qty=40" 10
  check "spoken" "^SAY .*약 2,300원 이득이야" 5
  hold 8

  scene 3 "응, 들를게" "승인하면 내비에 B 충전소를 넘긴다 (Pleos NaviHelper)"
  approve
  hold 6
  app_front
  geo 37.40200 127.10800 # B 충전소
  hold 3

  scene 4 "B 충전소에서 충전" "충전구 열림 · 충전 켜짐 → 40 kWh 충전 (Virtual Console로 차량 값을 바꾼다)"
  ev_put charge_port_open true; ev_put charge_switch true
  hold 3
  charge_ramp 19.35 40 8 2.5 77.4
  ev_put charge_switch false; ev_put charge_port_open false

  scene 5 "확인된 만큼만 적립" "차량 데이터로 40 kWh 확인 → 2,323원 확정 → 레벨 3 짜장면, 파스타까지 약 5,700원"
  check "detected 40 kWh at B" "^DETECT .*q=40\.000 basis=ENERGY .*station=ev-b" 30
  check "settled 2,323원 → level 3" "^SETTLE amount=2323 level=3 accrual=1323 remaining=5677" 10
  check "level-up line" "^SAY 짜장면 레벨 달성! 파스타까지 약 5,700원 남았어" 10
  check "level-up screen" "^SHOW level-up 3" 10
  # The character celebrates in place, then eats the new food in a loop.
  check "level-up clip" "^CLIP .*_levelup\.webp" 10
  check "eats 짜장면" "^CLIP .*_lv0?3_[a-z0-9]+_eat(_bolt[0-9]{3})?(_v[0-9]+)?\.webp" 15
  hold 3
  record_stop
}

play_a() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanDemo"
  trap 'record_stop' EXIT
  adb_s logcat -c
  record_start

  scene 0 "출발 전 경로 비교 (예시 데이터)" "강릉까지 고속도로·국도·혼합 경로. 운행비 + 시간 × 시급(5,000원)으로 비교한다. 이 비교는 절약액으로 쌓지 않는다"
  app_front --es zzan.demo A
  check "three routes compared" "^ROUTES dest=강릉 best=national rival=highway faster=-70 costMore=-7575 wage=5000 highway:37892\+13333 national:30317\+19167 mixed:34105\+16250" 15
  check "spoken" "^SAY 국도가 고속도로보다 70분 느리지만 약 7,600원 덜 들어\. 시급 5,000원으로 치면 국도가 나아!" 5
  hold 6
  app_cmd routesok
  check "card closed, the trip goes on" "^ROUTES closed" 5

  scene 1 "다른 날: 강릉 가는 길, 연료 10% (시연 데이터)" "기획서 4장 A-2·3. 휴게소에 정차, 연료가 알림 기준 아래 → 넣을 양을 먼저 묻는다 (출발 전 비교와는 다른 날의 장면)"
  check "low level" "^LOW level=10% threshold=20 parked=true" 15
  check "asks the amount" "^ASK fill reason=low" 10
  check "asked aloud, saying why" "^SAY 기름이 10% 남았어\. 얼마나 넣을까\?" 5
  SCN_TAG="ZzanTts" # 짠돌이 speaks with his own Pleos voice, not Gleo's (setSpeakerType)
  check "짠돌이's voice" "^focus granted speaker=sanghyeok" 10
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanDemo"
  hold 3

  scene 2 "30 L" "남은 6 L로 갈 수 있는 거리는 83 km(강릉까지 100 km): 목적지까지 못 간다. 주유소를 들르는 경로의 총 지출(연료비 + 가격 차이 + 통행료 + 시간비용)이 낮은 3곳을 보여 주고, C가 가장 비싼 B보다 676원 덜 든다"
  app_cmd fill --es zzan.arg q30
  check "destination out of range" "^ROUTE dest=강릉 remainKm=100 branch=UNREACHABLE limitKm=82\.6 reachable=route-a,route-b,route-c,route-d,route-e reach=no" 10
  check "three routes, the lowest total at 0" "^TOTAL ref=1855\.43 wage=5000 qty=30 route-c:15134\(0\) route-d:15450\(-316\) route-b:15810\(-676\)" 10
  check "recommendation" "^REC .*baseline=route-b best=route-c expected=676 mode=ROUTE_TOTAL rule=VS_WORST qty=30" 10
  check "spoken" "^SAY 이대로는 목적지까지 못 가\. 시간까지 치면 강릉길 C 주유소가 약 680원 덜 들어\. 들를까\?" 5
  hold 8

  scene 3 "응, 들를게" "승인하면 C 주유소로 안내한다"
  approve
  hold 3

  scene 4 "주유 → 출발 → 정차" "30 L 주유 확인 → 676원 확정(시간비용 포함). 주행 중에는 음성 한 줄만, 레벨업 화면은 정차한 뒤 (삼각김밥)"
  check "detected 30 L at C" "^DETECT .*q=30\.000 .*station=route-c" 30
  check "settled 676원 → level 1" "^SETTLE amount=676 level=1 accrual=176 remaining=2324" 10
  check "while driving: voice only" "^SAY 약 680원 아꼈어" 10
  check "after parking: level-up" "^SAY 삼각김밥 레벨 달성! 아메리카노까지 약 2,300원 남았어" 20
  check "level-up screen" "^SHOW level-up 1" 10
  check "level-up clip" "^CLIP .*_levelup\.webp" 10
  check "eats 삼각김밥" "^CLIP .*_lv0?1_[a-z0-9]+_eat(_bolt[0-9]{3})?(_v[0-9]+)?\.webp" 15
  hold 3
  record_stop
  app_front --es zzan.demo off
  info "back to live mode (실차 모드)"
}

play_drive() {
  drive_begin on
  scene 2 "주행 중 배터리 25%" "화면은 가려진 채, 짠순이가 짧게 묻고 5초 듣는다 (기획서 p.5 '양 또는 금액을 먼저')"
  adb_s logcat -c
  ev_put battery_level 19.35; ev_put soc 25
  check "low level while driving" "^LOW level=25% threshold=25 parked=false" 20
  check "asks by voice while driving" "^ASK fill reason=low" 10
  check "listens after the question" "^LISTEN expect=FILL window=5000" 15
  # The emulator has no microphone: feed what the recognizer would return.
  app_cmd hear --es zzan.arg "40킬로와트시"
  check "understood the amount" "^HEARD text=40킬로와트시 expect=FILL intent=Fill fill=q40" 10
  check "says the amount back, one place while driving" "^SAY 40kWh면, 집 가는 길 B 충전소가 약 2,300원 이득이야\\. 들를래\\?" 10
  check "listens for the answer" "^LISTEN expect=CONSENT window=5000" 15
  scene 3 "\"응\"" "이 Mac의 한국어 음성으로 만든 \"응\"을 Pleos SpeechToText(ON_DEVICE)로 인식 → 승인 → 내비에 B 충전소"
  if stt_clip yes "응"; then
    app_cmd hearpcm --es zzan.arg yes
    check "heard 응 (on-device STT)" "^HEARD text=응 expect=CONSENT intent=Yes" 25
  else
    info "the spoken answer could not be made or copied (macOS Korean voice Flo, adb): it is typed instead of recognized"
    app_cmd hear --es zzan.arg "응"
    check "heard 응 (typed)" "^HEARD text=응 expect=CONSENT intent=Yes" 10
  fi
  check "approved by voice while driving" "^DECIDE approved=true" 10
  hold 4

  scene 4 "정차" "P단에 서면 화면이 다시 보인다"
  park
  hold 3
  if [ "$(driving_state)" = 0 ]; then pass "car service: parked"; else fail "car service did not report parked ($(driving_state))"; fi
  hold 3
  record_stop
}

# drive_begin on|off: live session, seed, threshold 25 %, voice answers on/off, then drive at 30 km/h.
drive_begin() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  step "Virtual Console session"
  scenario_begin
  trap 'record_stop; app_cmd voice --es zzan.arg on; scenario_restore' EXIT
  info "original energy/soc: $SCN_ORIG (restored at the end)"
  ev_put battery_level 77.4; ev_put soc 100; ev_put charge_port_open false; ev_put charge_switch false
  geo 37.41272 127.09468
  adb_s shell am start -W -n "$SCN_APP" >/dev/null
  sleep 2
  adb_s logcat -c
  app_cmd seedB
  check "start: level 2 · 3,000원 · 짠순이" "^SEED level=2 accrual=3000" 10
  app_cmd threshold --es zzan.arg 25
  app_cmd voice --es zzan.arg "$1"
  record_start

  scene 1 "출발" "D단, 시속 30 km. 주행 중에는 시스템이 앱 화면을 가린다 (앱은 정차 전용 화면)"
  drive 30
  hold 5
  if [ "$(driving_state)" = 2 ]; then pass "car service: moving"; else fail "car service did not report moving ($(driving_state))"; fi
}

play_drive_quiet() {
  drive_begin off
  scene 2 "주행 중 배터리 25%" "음성 답을 끈 경우: 화면은 가려진 채 한 줄만 말한다. 답할 수 없는 질문은 하지 않는다"
  adb_s logcat -c
  ev_put battery_level 19.35; ev_put soc 25
  check "low level while driving" "^LOW level=25% threshold=25 parked=false" 20
  check "one line, no question" "^NOTICE low while driving" 5
  SCN_TAG="ZzanTts" # the speaker logs start/done when a line is actually played
  check "voice played while driving" "^done" 15
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  if applog | grep -qE "^REC |^ASK "; then fail "asked or recommended while driving"; else pass "no question, no recommendation while driving"; fi
  hold 4

  scene 3 "정차" "P단에 서면 화면이 다시 보이고, 그때 넣을 양을 묻는다 → 40 kWh → 추천"
  park
  hold 3
  if [ "$(driving_state)" = 0 ]; then pass "car service: parked"; else fail "car service did not report parked ($(driving_state))"; fi
  check "asks after stopping" "^ASK fill reason=low" 15
  app_cmd fill --es zzan.arg q40
  check "recommendation after stopping" "^REC .*reason=low baseline=ev-a best=ev-b expected=2323 mode=COST_INCLUDED rule=VS_NEAREST qty=40" 10
  hold 5
  app_cmd decline
  record_stop
}

# Level table (LevelTable.DEFAULT in core, 기획서 3-4): food and the cumulative saving that reaches each level.
play_habit() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanDemo"
  trap 'record_stop' EXIT
  adb_s logcat -c
  record_start

  scene 0 "시동을 켠 채 8분 (시연 데이터)" "레벨 1 삼각김밥 · 적립 300원에서 시작. 기어 P, 속도 0, 엔진이 도는 상태가 180초를 넘으면 그 뒤부터 공회전으로 친다"
  app_front --es zzan.demo C
  check "starts at level 1 with 300원" "^SEED level=1 accrual=300" 15
  check "the trip opens on the running engine" "^TRIP start id=trip-[0-9]+ by=engine" 10
  check "idling past 180 s" "^HABIT idle over 180s" 10
  check "one short line" "^SAY 시동 켠 채로 3분 넘었어\. 기름이 새고 있어!" 5

  scene 1 "급출발과 급가속" "출발하며 1초에 14 km/h(급출발), 시속 40에서 1초에 10 km/h(급가속). 한 번의 가속은 1건, 건당 136원(공격적 주행 1 km 상당). 급제동은 기록만 한다"
  check "harsh start" "^HABIT accel kind=RAPID_START v0=0\.0 a=14\.0 n=1 cost=136\.00" 15
  check "a short line, no amount while driving" "^SAY 어이쿠, 살살 밟자!" 5
  check "harsh acceleration" "^HABIT accel kind=RAPID_ACCEL v0=40\.0 a=10\.0 n=2 cost=136\.00" 15
  check "hard braking is recorded only" "^HABIT decel a=-14\.0 recorded" 10

  scene 2 "과속" "제한 100 km/h의 평가 속도는 110 km/h. 125 km/h로 6.25 km → 6.25 × 0.000064675 × 15 × 1,855.43 = 11.25원 (제한속도: 예시 데이터)"
  check "speeding starts above limit × 1.1" "^HABIT speeding start v=125\.0 limit=100 eval=110" 20
  check "a short line" "^SAY 조금만 천천히 가자!" 5

  scene 3 "주행 끝 · 정산" "272.01 + 93.65 + 11.25 = 376.90 → 377원을 한 번 차감. 레벨 적립액은 300원까지만 줄고 누적 1,500원(레벨 1 달성 금액)은 그대로, 이번 달 손익에는 377원 전부"
  check "trip totals" "^TRIP end id=trip-[0-9]+ accel=2:272\.01 idle=480s/300s:93\.65 speeding=6\.25km:11\.25 decel=1 raw=376\.90" 30
  check "deducted once, stopping at the level's accrual" "^PENALTY trip=trip-[0-9]+ full=377 applied=300 level=1 accrual=0" 5
  check "told after the stop" "^SAY 앗, 급가속, 공회전, 과속 때문에 이번 레벨 적립액이 다 줄었어…" 5
  hold 6
  local n
  n="$(applog | grep -c '^PENALTY ' || true)"
  if [ "$n" = 1 ]; then pass "one deduction for the trip"; else fail "one deduction for the trip (PENALTY lines: $n)"; fi
  record_stop
  app_front --es zzan.demo off
  info "back to live mode (실차 모드)"
}

LEVEL_FOODS=(빈\ 지갑 삼각김밥 아메리카노 짜장면 파스타 치킨 족발 스테이크 한우 랍스터 킹크랩)
LEVEL_WON=("0원" "1,500원" "4,000원" "8,000원" "15,000원" "26,000원" "42,000원" "66,000원" "100,000원" "150,000원" "250,000원")

play_levels() {
  # shellcheck disable=SC2034  # read by lib/scenario.sh
  SCN_TAG="ZzanApp"
  trap 'record_stop' EXIT
  app_front --es zzan.demo off # live screen; the car must be parked (P) for the level-up screen
  sleep 2
  adb_s logcat -c
  record_start
  local n
  for n in $(seq 0 10); do
    scene "$n" "레벨 $n ${LEVEL_FOODS[$n]}" "누적 절약 ${LEVEL_WON[$n]}부터 · $([ "$PERSONA" = zzansuni ] && echo 짠순이 || echo 짠돌이)"
    adb_s logcat -c # this level's lines only
    app_front --es zzan.cmd level --ei zzan.level "$n" --es zzan.persona "$PERSONA"
    if [ "$n" = 0 ]; then
      check "level 0 (start, no level-up)" "^SEED level=0 accrual=0" 10
    else
      check "level $n screen" "^SHOW level-up $n\$" 10
      check "level $n line" "^SAY ${LEVEL_FOODS[$n]} 레벨 달성" 10
      check "level $n clip" "^CLIP .*_levelup\.webp" 10
      check "level $n eats ${LEVEL_FOODS[$n]}" "^CLIP .*_lv0*${n}_[a-z0-9]+_eat(_bolt[0-9]{3})?(_v[0-9]+)?\.webp" 15
    fi
    hold 3
    # ZZAN_SHOTS_DIR=dir saves one screenshot per level (for documents and video thumbnails).
    if [ -n "${ZZAN_SHOTS_DIR:-}" ]; then adb_s exec-out screencap -p > "$ZZAN_SHOTS_DIR/level-$n.png"; fi
    hold 3
  done
  record_stop
}

printf '%s짠드라이브 demo%s: %s%s\n' "$C_B" "$C_0" "$SCENARIO" "$([ "$STEP" = 1 ] && echo ' (Enter로 진행)')"
case "$SCENARIO" in
  scenario-b) play_b ;;
  scenario-b-far) play_b_far ;;
  scenario-b-skip) play_b_skip ;;
  scenario-b-auto) play_b_auto ;;
  scenario-pass) play_pass ;;
  scenario-a) play_a ;;
  scenario-drive) play_drive ;;
  scenario-drive-quiet) play_drive_quiet ;;
  scenario-habit) play_habit ;;
  scenario-levels) play_levels ;;
esac

step "Result"
if [ "$SCN_FAILS" = 0 ]; then ok "$SCENARIO played and checked"; else die "$SCN_FAILS check(s) failed"; fi
