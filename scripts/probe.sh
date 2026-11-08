#!/usr/bin/env bash
# SDK 프로브 앱을 빌드 -> 설치 -> 실행하고 logcat 결과를 출력한다.
# 사용법: scripts/probe.sh [vehicle|navi|all]   (기본 all)
# 전제: scripts/emulator.sh start 로 에뮬레이터가 부팅되어 있어야 한다.
set -euo pipefail

SUITE="${1:-all}"
case "$SUITE" in
  vehicle) DEFAULT_WAIT=8 ;;
  navi)    DEFAULT_WAIT=50 ;;
  all)     DEFAULT_WAIT=56 ;;
  *) echo "usage: $0 [vehicle|navi|all]"; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$SDK/platform-tools:$PATH"

cd "$ROOT/tools/sdk-probe"
./gradlew assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.zzandrive.probe
adb shell am start -n com.zzandrive.probe/.ProbeActivity --es suite "$SUITE" >/dev/null
sleep "${PROBE_WAIT:-$DEFAULT_WAIT}"
adb logcat -d -s ZzanProbe:I | cut -c19-
