#!/usr/bin/env bash
# 시험용 임시 영상·3D·라이브러리를 내려받는다 (git에 올라가지 않음).
# 출처와 라이선스는 아래 주석 참고. 제품 에셋이 아니다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/tools/sdk-probe/app/src/main/assets/media"
mkdir -p "$DIR"
get() { echo "-> $2"; curl -fsSL --max-time 90 -o "$DIR/$2" "$1"; }

# Big Buck Bunny 10초 360p (Blender Foundation, CC BY 3.0) — H.264 / VP9 비교용
get "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4" sample_h264.mp4
get "https://test-videos.co.uk/vids/bigbuckbunny/webm/vp9/360/Big_Buck_Bunny_360_10s_1MB.webm" sample_vp9.webm

# 같은 영상의 다른 코덱·해상도 (코덱/해상도별 시작 지연 비교용)
BB="https://test-videos.co.uk/vids/bigbuckbunny"
get "$BB/mp4/h265/360/Big_Buck_Bunny_360_10s_1MB.mp4" sample_hevc.mp4
get "$BB/mp4/av1/360/Big_Buck_Bunny_360_10s_1MB.mp4" sample_av1.mp4
get "$BB/mp4/h264/720/Big_Buck_Bunny_720_10s_2MB.mp4" sample_h264_720p.mp4
get "$BB/mp4/h264/1080/Big_Buck_Bunny_1080_10s_5MB.mp4" sample_h264_1080p.mp4
get "$BB/webm/vp9/1080/Big_Buck_Bunny_1080_10s_5MB.webm" sample_vp9_1080p.webm

# AVIF 정지 이미지 (link-u/avif-sample-images, 여우 사진)
get "https://raw.githubusercontent.com/link-u/avif-sample-images/master/fox.profile0.8bpc.yuv420.avif" sample.avif

# Khronos glTF Sample Assets: Fox (모델 CC0, 애니메이션 CC-BY 4.0), 뼈대 애니메이션 포함
get "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb" fox.glb

# three.js r147 (MIT) — WebView에서 file/모듈 제약 없이 쓰는 비모듈 빌드
get "https://cdn.jsdelivr.net/npm/three@0.147.0/build/three.min.js" three.min.js
get "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/loaders/GLTFLoader.js" GLTFLoader.js

ls -la "$DIR"
