#!/usr/bin/env bash
# 에뮬레이터에 이미지/애니메이션/영상/3D를 띄워 본다 (MediaActivity).
# 사용법: scripts/media.sh <image|anim|gif|video|webm|glb|codecs|clear> [대기초] [gate=true|false]
#   gate=true(기본): 기어가 P(주차)일 때만 표시 (기획서: 연출은 정차·주차 중에만)
# 전제: scripts/emulator.sh start, scripts/probe.sh 로 앱이 설치되어 있고,
#       scripts/gen-test-media.py 와 scripts/fetch-test-media.sh 로 시험 에셋을 만든 뒤 빌드했을 것.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$SDK/platform-tools:$PATH"
KIND="${1:?usage: $0 <image|anim|gif|video|webm|glb|codecs|clear> [wait] [gate]}"
WAIT="${2:-8}"
GATE="${3:-true}"

# 이미 열린 화면은 새 명령을 받지 못하는 경우가 있어 매번 새로 시작한다.
adb shell am force-stop com.zzandrive.probe
sleep 1
adb logcat -c
adb shell am start --user 10 -n com.zzandrive.probe/.MediaActivity --es kind "$KIND" --ez gate "$GATE" >/dev/null
sleep "$WAIT"
adb logcat -d -s ZzanProbe:I | grep "MEDIA" | grep -v "SOC update" | sed -E 's/^.*ZzanProbe: MEDIA //'
