#!/usr/bin/env bash
# Pleos 에뮬레이터를 터미널에서 제어한다 (Android Studio 불필요).
# 사용법: scripts/emulator.sh start | stop | status | console
set -euo pipefail

AVD_NAME="${AVD_NAME:-Pleos_Connect_v2.1.0}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$SDK/emulator:$SDK/platform-tools:$PATH"
LOG="${TMPDIR:-/tmp}/zzan-emulator.log"

booted() { [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

case "${1:-status}" in
  start)
    if pgrep -f "qemu-system.*-avd $AVD_NAME" >/dev/null; then
      echo "이미 실행 중입니다."
    else
      nohup emulator -avd "$AVD_NAME" >"$LOG" 2>&1 &
      echo "에뮬레이터 시작 (로그: $LOG)"
    fi
    echo -n "부팅 대기"
    for _ in $(seq 1 90); do booted && { echo " ... 완료"; break; }; echo -n "."; sleep 3; done
    booted || { echo; echo "부팅 시간 초과. 로그를 확인하세요: $LOG"; exit 1; }
    adb forward tcp:8088 tcp:8088 >/dev/null
    echo "Virtual Console: http://127.0.0.1:8088/  (브라우저로 열어야 값 변경이 활성화됩니다)"
    ;;
  stop)
    adb emu kill || true
    ;;
  status)
    adb devices | tail -n +2
    booted && echo "booted" || echo "not booted"
    ;;
  console)
    adb forward tcp:8088 tcp:8088
    open "http://127.0.0.1:8088/" 2>/dev/null || echo "http://127.0.0.1:8088/ 를 브라우저로 여세요."
    ;;
  *)
    echo "usage: $0 start|stop|status|console"; exit 2 ;;
esac
