#!/usr/bin/env bash
# Pleos 에뮬레이터를 터미널에서 제어한다 (Android Studio 불필요).
# 사용법: scripts/emulator.sh start | stop | status | console
# AVD는 자동 탐지한다 (pleos_car_emulator 이미지를 쓰는 AVD, Pleos_Connect_v2.1.0 우선).
# 특정 AVD를 쓰려면 AVD_NAME=이름 scripts/emulator.sh start
# 설치·AVD 생성까지 한 번에 하려면 scripts/bootstrap.sh 를 쓴다.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"
detect_platform; resolve_sdk_root
[ -n "${AVD_NAME:-}" ] && ZZAN_AVD="$AVD_NAME"

booted() { serial_online && [ "$(adb_s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

case "${1:-status}" in
  start)
    ZZAN_AVD="$(find_pleos_avd)" || die "Pleos AVD를 찾지 못했습니다. scripts/bootstrap.sh 로 이미지와 AVD를 준비하세요."
    start_emulator
    console_forward
    echo "Virtual Console: $ZZAN_CONSOLE_URL/  (브라우저로 열어 두면 값 변경이 활성화됩니다)"
    ;;
  stop)
    adb_s emu kill || true
    ;;
  status)
    "$ADB" devices | tail -n +2
    if booted; then echo "booted ($(running_avd_name))"; else echo "not booted"; fi
    ;;
  console)
    console_forward
    open "$ZZAN_CONSOLE_URL/" 2>/dev/null || echo "$ZZAN_CONSOLE_URL/ 를 브라우저로 여세요."
    ;;
  *)
    echo "usage: $0 start|stop|status|console"; exit 2 ;;
esac
