# 캐릭터 연출(이미지·애니메이션·영상·3D) 표시 가능성

짠드라이브는 모은 금액에 맞춰 캐릭터가 음식을 먹는 연출을 보여 줍니다. 이 문서는 ① 기획서에 연출이 **어느 시점에, 어떤 형태로** 적혀 있는지, ② 그것을 **에뮬레이터에 실제로 띄울 수 있는지** 시험한 결과를 정리합니다.

- 시험일: 2026-11-08 / Pleos Connect Emulator 2.1.0 (Android 14, API 34, 전기차 이미지) / Mac M1 Pro
- 시험 코드: [tools/sdk-probe `MediaActivity`](../../tools/sdk-probe/app/src/main/java/com/zzandrive/probe/MediaActivity.kt)
- **시험에 쓴 이미지·영상·3D는 모두 임시 에셋**입니다 (캐릭터 시안이 아님). 판정은 "이 형식을 이 에뮬레이터가 그릴 수 있는가"에 대한 것입니다.

## 1. 기획서에 적힌 연출 (읽은 문서)

확인한 문서: `짠드라이브 기획서.docx`(11/6), `261107 짠드라이브_기획서(계산식 정리).docx`(11/8 수정본). 두 문서의 연출 관련 문장은 동일합니다. (PDF는 직접 열지 않았고 같은 내용의 docx로 확인했습니다.)

### 1.1 연출이 나오는 시점

| 시점 | 기획서 근거 | 보여 주는 것 |
| --- | --- | --- |
| **레벨업** (확정 절약으로 적립액이 목표액을 채울 때) | "레벨업 연출과 캐릭터 애니메이션은 정차·주차 중에만 보여줍니다" / 시나리오 A "삼각김밥 레벨 달성!" | 레벨업 연출, 새 레벨의 음식 |
| **도착 후 정차** | 시나리오 A "화면에서 짠돌이가 삼각김밥을 먹는 모습과 급가속 횟수를 확인합니다" | **현재 레벨의 음식을 먹는 캐릭터** + 오늘 손익·급가속 횟수 |
| 정차·주차 중 | 안전 설계 표 "정차·주차 중: 캐릭터 애니메이션, 레벨업 연출, 절약 리포트, 오늘·이번 달 손익" | 절약 리포트, 월간 손익 화면 |
| 비효율 주행 감지 (주행 중) | "귀엽게 칭얼거리는 정도로만 반응", "일정 시간 부드럽게 주행하면 캐릭터의 기분이 풀립니다" | 주행 중에는 **짧은 음성 한 줄**만 (화면 연출 없음) |
| 첫 실행 | 짠돌이/짠순이 선택 | 캐릭터 선택 화면 |
| 구현 단계 | 접수 전(~11/15) "캐릭터 레벨업 화면", PT 전(~12/2) "레벨 0~10 캐릭터 연출(짠돌이·짠순이)" | M1은 레벨업 화면, M2는 전 레벨 연출 |

### 1.2 연출 내용 (레벨표의 "캐릭터 모습")

레벨 0 빈 지갑을 거꾸로 털기 / 1 삼각김밥 포장을 뜯어 먹기 / 2 아메리카노 마시기 / 3 짜장면 후루룩 / 4 파스타 돌돌 말아 먹기 / 5 치킨 뜯기 / 6 족발 뜯기 / 7 스테이크 썰어 먹기 / 8 한우 구워 먹기 / 9 랍스터 통째로 먹기 / 10 킹크랩 다리 뜯기. 그리고 "음식은 … **배경 없이 캐릭터와 음식만으로** 알아볼 수 있는 메뉴로 골랐습니다."

### 1.3 기획서에 **적혀 있지 않은 것** (결정이 필요)

| 항목 | 왜 필요한가 |
| --- | --- |
| 연출 **형식** (영상, 2D 애니메이션, 3D 중 무엇인가) | 형식에 따라 제작 방식과 기술이 달라짐. "영상"이라는 표현은 기획서에 없고 "애니메이션"이라고만 적혀 있음 |
| 연출 **분량** | 레벨 0~10 × 캐릭터 2종 = 최소 22개, 여기에 레벨업 전환·칭얼거림·기분 풀림이 더해질지 |
| 재생 **길이·반복** | 도착 후 정차 화면에서 반복 재생인지, 한 번인지 |
| **여러 단계 레벨업**(R-41, 한 번에 둘 이상 오름) 때의 연출 순서 | 연속 재생할지 마지막만 보여 줄지 |
| 레벨업이 **주행 중에** 일어났을 때 연출 시점 | "정차 중에만" 이므로 정차 후로 미루는 큐가 필요. 얼마나 쌓아 둘지 |
| 정차 판정 기준 | spec.md D-15와 같은 문제 |
| **투명 배경** 요구의 기술적 의미 | "배경 없이"는 알파(투명) 채널이 필요. 일반 MP4는 알파를 지원하지 않음 (4장 참고) |
| 에셋 제작자·일정 | 기획서에 없음. M1 마감(11/15)에 쓸 임시 에셋 범위 |

## 2. 정차·주차 중에 표시되는가 (R-33)

기획서의 "연출은 정차·주차 중에만" 규칙을 시험했습니다. `MediaActivity`는 연출을 띄우기 직전에 **기어를 읽어 P(주차)일 때만** 표시합니다(`gate=true`, 기본값).

| 확인 | 결과 | 근거 |
| --- | :-: | --- |
| 기어 P일 때 이미지·애니메이션·영상·3D가 표시되는가 | ✅ | 로그 `gate OK: gear=GEAR_PARK -> showing 'image'` 다음에 표시됨. 3장의 모든 표시 시험은 이 조건 아래에서 실행됨 |
| 표시 시점을 원하는 때로 정할 수 있는가 | ✅ | 터미널 명령(`scripts/media.sh`)으로 지정한 순간에 표시됨. 제품에서는 이 자리에 "확정 절약 발생" 같은 이벤트가 들어감 |
| 기어가 P가 아니면 막히는가 | ❓ 미확인 | 코드는 있으나 기어를 D로 바꿔 시험하지 못함 (Virtual Console에서 기어 변경 필요) |
| "정차" 상태(기어 D + 속도 0, 신호 대기 등)도 허용되는가 | ❌ 미구현 | 현재는 기어 P만 허용. 정차 판정 기준은 spec.md D-15 (속도 조회가 Vehicle SDK에 없음, [api-feasibility.md](api-feasibility.md)) |
| 배터리 SOC 상승으로 자동 표시되는가 | ❓ 미확인 | 코드는 있으나 SOC를 올려 보는 시험은 못 함 |
| 주행 중 발생한 레벨업을 정차 후로 미루는 큐 | ❌ 미구현 | 설계 필요 (D-18) |

## 3. 형식별 지원 현황 (구현 참고)

시험 방법: `scripts/media.sh matrix`가 `assets/media/`의 모든 파일을 차례로 열어 `OK/FAIL`과 수치를 기록합니다. 기준은 **"표시 시작까지 성공했는가"**이며, 일부는 화면 캡처로 눈으로도 확인했습니다 (표의 "육안" 열).

### 3.1 이미지·애니메이션

| 형식 | 확장자 / MIME | 결과 | 알파 | 움직임 | 육안 | 크기(시험 파일) | 비고 |
| --- | --- | :-: | :-: | :-: | :-: | --: | --- |
| PNG | `.png` `image/png` | ✅ | ✅ | 정지 | ✅ | 4 KB | 투명 배경 정상 |
| **APNG** | `.apng` / `.png` | ⚠️ | ✅ | **움직이지 않음** | ✅ | 21 KB | **첫 프레임만 표시되고 애니메이션은 재생되지 않음.** 쓰지 말 것 |
| GIF | `.gif` `image/gif` | ✅ | 1비트 | 움직임 | — | 33 KB | 반투명·부드러운 가장자리 불가. 같은 그림에서 WebP보다 2배 큼 |
| **WebP (무손실, 애니메이션)** | `.webp` `image/webp` | ✅ | ✅ | 움직임 | ✅ | **16 KB** | 12프레임 480×480. 이 단순 그림에서는 가장 작고 알파도 정상 |
| WebP (손실, 애니메이션) | `.webp` | ✅ | ✅ | 움직임 | ✅ | 54 KB | 알파 정상. 단순 도형에서는 무손실이 더 작았음 |
| WebP (정지, 손실) | `.webp` | ✅ | 없음 | 정지 | — | 5 KB | 이 파일은 알파가 없음 |
| WebP (정지, 손실+알파) | `.webp` | ✅ | ✅ | 정지 | — | 6 KB | 알파 정상 |
| JPEG | `.jpg` `image/jpeg` | ✅ | 없음 | 정지 | — | 16 KB | 투명 배경이 필요한 캐릭터에는 부적합 |
| BMP | `.bmp` `image/bmp` | ✅ | 없음 | 정지 | — | 691 KB | 압축이 없어 비권장 |
| AVIF | `.avif` `image/avif` | ✅ | 없음(이 파일) | 정지 | — | 81 KB | 1204×800 사진, **디코드 480 ms**(소프트웨어). 알파 AVIF·애니메이션 AVIF는 미시험 |
| HEIC / HEIF | — | ❓ | — | — | — | — | 시험 파일이 없어 미시험 |
| **Lottie (벡터)** | `.json` | ✅ | ✅ | 움직임 | ✅ | **1 KB** | `com.airbnb.android:lottie:6.4.0` 필요, 구성 로딩 51~271 ms. 간단한 도형 예제만 시험 |
| 대형 애니메이션 WebP | `.webp` | ✅ | — | 움직임 | — | 1.9 MB | 1024×1024, 40프레임. 시작은 정상이나 **재생 프레임 속도는 측정하지 못함** |

### 3.2 영상

모두 `VideoView`(표준 MediaPlayer)로 시험했습니다. "첫 화면"은 재생 요청부터 첫 프레임이 그려질 때까지이며, 일괄 시험에서 각 파일의 앞부분(첫 프레임까지)만 확인했습니다.

| 컨테이너 / 코덱 | 해상도 | 결과 | 첫 화면 | 비트레이트 | 비고 |
| --- | --- | :-: | --: | --: | --- |
| MP4 / H.264 | 640×360 | ✅ | 247 ms | 792 kbps | 10초 전체 재생도 확인 (`completed`) |
| MP4 / H.264 | 1280×720 | ✅ | 246 ms | 1,582 kbps | |
| MP4 / H.264 | 1920×1080 | ✅ | 627 ms | 4,190 kbps | |
| MP4 / HEVC (H.265) | 640×360 | ✅ | 278 ms | 846 kbps | |
| MP4 / AV1 | 640×360 | ✅ | 199 ms | 836 kbps | **소프트웨어 디코더만** 있음. 고해상도는 미시험 |
| WebM / VP9 | 640×360 | ✅ | 185 ms | 842 kbps | 10초 전체 재생도 확인 |
| WebM / VP9 | 1920×1080 | ✅ | 146 ms | 4,194 kbps | |
| WebM / VP8 | — | ❓ | — | — | 디코더는 있으나 시험 파일이 없어 미시험 |
| MKV, 3GP, MOV | — | ❓ | — | — | **미시험.** 받을 수 있는 유효한 샘플이 없었고, 만들려면 ffmpeg가 필요한데 설치가 허용되지 않음. (`.mkv`로 받은 파일은 실제로는 MP4였음) |
| **알파(투명) 영상** | — | ❓ | — | — | **미시험.** 투명 영상을 만들 도구가 없었음. 기획서의 "배경 없이"에 해당하는 핵심 항목이라 별도 확인이 필요 |

- **첫 실행은 느립니다.** 앱을 새로 시작한 뒤 처음 재생한 H.264는 첫 화면까지 **2.2초**였고, 이후에는 0.2~0.6초였습니다 (코덱 초기화 때문으로 추정).
- 에뮬레이터 디코더: H.264, HEVC, VP8, VP9는 하드웨어(`c2.goldfish.*`)와 소프트웨어 모두, AV1은 소프트웨어(`c2.android.av1.decoder`)만 있습니다. 실제 차량 단말은 다를 수 있습니다.

### 3.3 3D 모델

| 형식 | 결과 | 상세 |
| --- | :-: | --- |
| glTF Binary `.glb` (WebView + three.js r147) | ✅ | Khronos Fox(160 KB, 뼈대 애니메이션 3종). WebGL 2.0, 투명 배경, **12~17 fps**, 페이지 로딩 약 4.5초. WebView는 Pleos 전용 `ai.pleos.android.webview` 131.0.6752.0 |
| 네이티브 3D 엔진(Filament 등) | ❓ 미시험 | |

## 4. 구현 가이드

시험 코드: [MediaActivity.kt](../../tools/sdk-probe/app/src/main/java/com/zzandrive/probe/MediaActivity.kt)

**정지 이미지 / 애니메이션 이미지 (PNG, WebP, GIF, AVIF, JPEG)** — `ImageDecoder`가 정지/움직임을 자동으로 구분합니다 (API 28+, 앱 minSdk 28과 일치).

```kotlin
val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "media/char_eating.webp"))
imageView.setImageDrawable(d)
if (d is AnimatedImageDrawable) {
    d.repeatCount = 2                       // 또는 REPEAT_INFINITE
    d.registerAnimationCallback(object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) { /* 연출 종료 처리 */ }
    })
    d.start()
}
```

**영상 (MP4, WebM)** — `VideoView`는 파일 경로가 필요해서 assets를 캐시 폴더로 복사한 뒤 재생했습니다.

```kotlin
val f = File(cacheDir, name).also { if (!it.exists()) assets.open("media/$name").use { i -> it.outputStream().use { o -> i.copyTo(o) } } }
videoView.setOnPreparedListener { it.start() }
videoView.setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { /* 첫 프레임 */ }; true }
videoView.setOnCompletionListener { /* 종료 */ }
videoView.setOnErrorListener { _, w, e -> /* 실패 */ true }
videoView.setVideoPath(f.absolutePath)
```

**Lottie** — 의존성 `com.airbnb.android:lottie:6.4.0` **과** `androidx.appcompat:appcompat` 이 필요합니다 (`LottieAnimationView`가 `AppCompatImageView`를 상속하므로, appcompat이 없으면 컴파일 오류).

```kotlin
val lv = LottieAnimationView(this)
lv.setAnimationFromJson(json, "key"); lv.repeatCount = 0; lv.playAnimation()
```

**3D** — WebView에 번들한 three.js로 `.glb`를 그렸습니다. `androidx.webkit:webkit`의 `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/...` 주소에서 에셋을 제공해야 `file://` 제약 없이 `.glb`를 읽습니다. 투명 배경은 `WebView.setBackgroundColor(TRANSPARENT)` + `WebGLRenderer({alpha:true})`.

**정차 확인 (표시 직전)**

```kotlin
vehicle.getGear().getCurrentGearSelectionState({ gear ->
    if (gear.toString().contains("PARK")) show() else { /* 큐에 보관 */ }
}, { e -> /* 읽기 실패 정책 필요 */ })
```

권한: 위 시험에서 미디어 표시 자체는 추가 권한이 필요 없었습니다. 기어 읽기는 `CAR_DRIVE`(이미 선언).

## 5. 용도별 형식 선택 (제안)

| 용도 | 권장 | 이유 |
| --- | --- | --- |
| 캐릭터가 음식을 먹는 연출 (투명 배경) | **WebP 애니메이션** (무손실부터 시도), 벡터로 만들 수 있으면 Lottie | 알파·재생 정상, 용량 작음 |
| 레벨업 전환 효과 | WebP 애니메이션 또는 Lottie | 같음 |
| 풀스크린 배경이 있는 연출 영상 | MP4 H.264 | 가장 호환성이 높고 하드웨어 디코더 있음 |
| 용량·속도가 중요한 영상 | WebM VP9 | 첫 화면이 가장 빨랐음 (146~185 ms) |
| 정지 캐릭터·아이콘 | PNG 또는 WebP | 알파 지원 |
| 3D 캐릭터 | GLB (WebView) | 가능하나 12~17 fps. 에셋 제작 비용이 큼 |
| **피할 것** | APNG, GIF, BMP, JPEG(투명 필요 시) | APNG는 움직이지 않음, GIF는 알파가 거칠고, BMP는 큼, JPEG는 알파 없음 |

참고: 이 측정은 **단순한 도형 그림** 기준입니다. 실제 일러스트·그라데이션이 들어가면 같은 형식에서도 용량이 몇 배 이상 커질 수 있고, 무손실이 손실보다 작다는 결과는 뒤집힐 수 있습니다. 시험 에셋 전체는 약 20 MB였습니다.

## 6. 시험하지 못했거나 모르는 것

| 항목 | 상태 |
| --- | --- |
| 기어가 P가 아닐 때의 차단 | 코드만 있음, 미확인 (Virtual Console에서 기어 변경 필요) |
| 정차(D + 속도 0) 허용, 레벨업 큐 | 미구현 (D-15, D-18) |
| SOC 상승 자동 트리거 | 코드만 있음, 미확인 |
| **알파(투명) 영상** (WebM VP9 알파, HEVC 알파 등) | 미시험 (도구 부족). 투명 배경이 영상이어야 한다면 먼저 확인 필요 |
| MKV / 3GP / MOV / VP8 | 미시험 |
| HEIC / HEIF, 알파·애니메이션 AVIF | 미시험 |
| 대형 애니메이션의 **재생 프레임 속도**, 메모리 사용량 | 측정하지 못함 (시작 성공만 확인) |
| 실제 캐릭터 에셋(복잡한 일러스트)의 용량·성능 | 임시 에셋이라 알 수 없음 |
| 실제 차량 단말(에뮬레이터와 디코더·GPU가 다를 수 있음) | 미시험 |
| 열려 있는 화면에 두 번째 `am start` 명령 전달 | 반응하지 않았고 원인 모름. 시험은 매번 새로 시작함 |
| 에뮬레이터 앱 창 크기 | 화면 오른쪽 일부(약 1682×1210)만 사용. 연출은 이 크기 기준으로 설계 |

## 7. 재현 방법

```bash
python3 scripts/gen-test-media.py        # 투명 PNG/WebP/GIF/APNG/Lottie 등 임시 에셋 생성 (Pillow 필요)
scripts/fetch-test-media.sh              # 영상·AVIF·3D·three.js 임시 에셋 내려받기
scripts/probe.sh vehicle                 # 앱을 빌드·설치 (에셋이 앱에 포함됨)
scripts/media.sh matrix 90               # 모든 형식 일괄 시험 (결과: MATRIX 줄)
scripts/media.sh image                   # 개별 표시: image | anim | gif | video | webm | glb | codecs | clear
scripts/media.sh anim 8 false            # 기어 확인 없이 표시 (gate=false)
```

특정 파일 하나를 화면에 띄우려면:

```bash
adb shell am start --user 10 -n com.zzandrive.probe/.MediaActivity --es kind file --es path media/char_lottie.json --ez gate false
```

임시 에셋은 `tools/sdk-probe/app/src/main/assets/media/`에 생기며 git에 올라가지 않습니다. 출처와 라이선스는 `scripts/fetch-test-media.sh` 주석에 있습니다 (Big Buck Bunny CC BY 3.0, Khronos Fox 모델 CC0·애니메이션 CC BY 4.0, three.js MIT, AVIF 샘플은 link-u/avif-sample-images).

**직접 확인해 보실 것:**

1. Virtual Console에서 기어를 **D로 바꾼 뒤** `scripts/media.sh anim`을 실행하세요. 로그에 `BLOCKED ... car is not parked`가 나오고 화면에 안 뜨면 차단이 정상입니다.
2. `scripts/media.sh clear 3`으로 화면을 앞에 띄운 뒤 **Battery SOC를 올려** 애니메이션이 자동으로 뜨는지 보세요.
