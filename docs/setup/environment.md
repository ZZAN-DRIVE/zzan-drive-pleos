# 개발 환경 세팅 (Pleos 에뮬레이터 + SDK 테스트)

짠드라이브 팀원이 **에뮬레이터를 띄우고, 내 코드가 차량 SDK와 통하는지 확인**하기까지의 절차입니다.

- ✅ **Mac(Apple Silicon)** 은 2026-11-08에 실제로 검증했습니다.
- ⚠️ **Windows** 는 공식 문서 기준으로 정리했고 **직접 검증하지 못했습니다.** 막히면 이 문서를 고쳐 주세요.
- 제품 언어(spec.md D-13)는 미정입니다. 여기서 쓰는 Kotlin은 **SDK 확인용 프로브 앱에만** 쓴 것입니다.

## 0. 한눈에 보기

```
┌─ 내 컴퓨터 ───────────────────────────────────────────────┐
│  VS Code 터미널                                           │
│    scripts/emulator.sh start ─▶ 에뮬레이터(가상 차량) 실행 │
│    scripts/probe.sh          ─▶ 프로브 앱 빌드·설치·실행   │
│                                                           │
│  브라우저 http://127.0.0.1:8088/  ─▶ Virtual Console      │
│    (슬라이더로 배터리·주행거리 같은 차량 값을 바꿈)         │
└───────────────────────────────────────────────────────────┘
```

| 포트 | 용도 | 열어야 하나 |
| --- | --- | --- |
| **8088** | Virtual Console (차량 값을 바꾸는 웹 화면) | ✅ 브라우저로 `http://127.0.0.1:8088/` |
| 5554 / 5555 | 에뮬레이터 콘솔 / adb 연결 (에뮬레이터가 자동 사용) | ❌ 직접 열 일 없음 |

> Virtual Console 화면, 차량 화면, Vehicle SDK는 **Pleos가 제공**하는 것입니다. 우리가 만든 것은 `tools/sdk-probe`와 `scripts/`뿐입니다.

## 1. 한 번만 하는 설치

### 1-0. `.env` (선택)

Playground에서 발급받은 프로젝트 ID, 클라이언트 ID/시크릿은 `.env.example`을 복사해 `.env`에 적습니다 (`.env`는 git에 올라가지 않음). **Vehicle SDK와 NaviHelper SDK 시험에는 필요하지 않습니다.** TTS/STT 같은 서버 연결형 SDK가 열릴 때 사용합니다.

```bash
cp .env.example .env     # 이후 값을 채움
```

### 1-1. 직접 해야 하는 것 (사람만 가능)

| 항목 | 이유 |
| --- | --- |
| **Pleos Playground 가입, 프로젝트 생성** | 로그인 필요. TTS/STT/FusedLocation 신청에 쓰는 ID가 여기서 나옴 (Vehicle·NaviHelper는 신청 없이 사용 가능) |
| **42dot Pleos Connect SDK License 동의** | 법적 동의. 에뮬레이터 이미지 설치 시 나오는 화면에서 본인이 수락 |
| (Mac) Command Line Tools 업데이트 | 시스템 설정 → 소프트웨어 업데이트. `brew`를 쓸 때만 필요 |

### 1-2. 필요한 도구

| 도구 | 버전 | 비고 |
| --- | --- | --- |
| JDK | **17** | Gradle·AGP용 |
| Android SDK | cmdline-tools, platform-tools, emulator, `platforms;android-35`, `build-tools;35.0.0` | |
| Pleos 에뮬레이터 이미지 | Pleos Connect v2.1.0 (Android 14 / API 34) | 약 4.7GB, 디스크 여유 필요 |
| Android Studio | 최신 | **최초 설치(이미지·AVD 생성)에만** 사용. 이후에는 불필요 |

### 1-3. Pleos 에뮬레이터 이미지 설치 + AVD 생성

Android Studio에서 한 번만 합니다.

1. **Settings → Languages & Frameworks → Android SDK → SDK Update Sites** 에 아래 주소를 추가합니다.
   - Mac(Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows / Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms → Show Package Details** 에서 `Pleos Connect` 이미지를 설치합니다. 라이선스 화면이 나오면 읽고 직접 수락합니다.
3. **Device Manager → Create Virtual Device**
   - Form Factor: **Automotive** → 이미지: **Pleos Connect v2.1.0 (API 34)**
   - 이름: `Pleos_Connect_v2.1.0` (스크립트가 이 이름을 기본값으로 씀)
   - Advanced: **Boot option = Cold boot**, **RAM = 4GB**
4. 설치 확인 (터미널):
   ```bash
   emulator -list-avds        # Pleos_Connect_v2.1.0 이 보여야 함
   ```

### 1-4. 환경변수 (Mac)

`~/.zshrc` 에 아래를 추가하고 새 터미널을 엽니다.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

확인:

```bash
java -version      # 17 이어야 함
adb version
emulator -version
```

## 2. 매번 하는 순서 (Android Studio 불필요)

프로젝트 루트(`zzan-drive-pleos`)에서 VS Code 터미널로 실행합니다.

### ① 에뮬레이터 실행

```bash
scripts/emulator.sh start
```

- 부팅이 끝날 때까지 기다린 뒤(약 25초) `adb forward tcp:8088 tcp:8088` 까지 자동으로 해 줍니다.
- 에뮬레이터 창이 뜨면 정상입니다. 로그는 `$TMPDIR/zzan-emulator.log` 에 남습니다.
- 상태 확인 / 종료: `scripts/emulator.sh status` / `scripts/emulator.sh stop`

### ② Virtual Console 열기 (브라우저)

주소: **http://127.0.0.1:8088/**

```bash
scripts/emulator.sh console     # 브라우저를 대신 열어 줌 (Mac)
```

> ⚠️ **이 페이지를 브라우저에 열어 둔 동안에만** 값 변경이 활성화됩니다. 하단 상태 표시줄이 **`DDS ONLINE`** 이면 활성화된 것이고, `DDS OFFLINE` 이면 값이 바뀌지 않습니다. 터미널(`curl` 등)로 값을 바꾸려 하면 `emulator_inactive` 오류가 납니다 (브라우저 세션이 아니기 때문). **값 변경은 브라우저에서 직접** 하세요.

**배터리 값을 바꾸는 위치:**

1. 화면 왼쪽 중간의 **Test Console → Emulation Controls** 카드를 누릅니다 (`48 features · 7 domains`).
2. 왼쪽 목록에서 **Energy** 를 고릅니다.
3. 슬라이더가 나옵니다: **Battery SOC**(0~100 %), Battery Energy, Battery Capacity, Remaining Range, Charge Current/Percent Limit, Charge Switch(Off/On), Charge Port(Closed/Open). 위쪽 검색창에 `SOC` 를 입력해도 됩니다.
4. 각 카드 오른쪽 위의 **초록색 숫자가 현재 적용된 값**입니다. 슬라이더를 움직인 뒤 초록 숫자가 따라 바뀌어야 적용된 것입니다.

### ③ 프로브 앱으로 SDK 확인

```bash
scripts/probe.sh            # vehicle + navi 모두 (약 1분)
scripts/probe.sh vehicle    # Vehicle State만 (약 8초)
scripts/probe.sh navi       # NaviHelper만 (약 50초)
scripts/probe.sh loc        # FusedLocation (먼저 권한 부여, 문제 해결 표 참고)
scripts/probe.sh tts        # TextToSpeech
scripts/probe.sh control    # Vehicle 제어 API (상태를 바꾸지 않음)
```

빌드 → 설치 → 실행 → 결과 출력까지 한 번에 합니다. 각 항목의 결과 해석은 [API 사용 가능성 테스트 결과](../references/api-feasibility.md)에 있습니다. 처음 실행 시 Gradle과 라이브러리를 내려받아 **몇 분** 걸립니다. 결과는 두 곳에 나옵니다.

- 에뮬레이터 화면의 `ZZAN SDK Probe` 앱 (하단 중앙 Android 아이콘으로 다시 열 수 있음)
- 터미널 로그: `adb logcat -s ZzanProbe:I`

### ④ 값을 바꿔서 앱이 알아채는지 보기

> 헷갈리기 쉬운 점: **차량 내장 화면**(왼쪽 상단 `500 km`, 설정 앱의 충전 화면 등)도 값을 바꾸면 같이 바뀝니다. 이것은 Pleos 기본 앱이 반응하는 것이고, **우리 코드가 감지했다는 증거가 아닙니다.** 우리 코드의 증거는 `ZZAN SDK Probe` 의 로그입니다.

**A. 바뀐 값을 다시 읽기 (✅ 2026-11-08 확인)**

1. 브라우저(Emulation Controls → Energy)에서 값을 바꿉니다. 예) Charge Port → Open, Battery Energy → 63.3 kWh.
2. `scripts/probe.sh` 를 다시 실행하거나 `ZZAN SDK Probe` 앱을 다시 엽니다.
3. 바뀐 값이 읽히는지 확인합니다. 예) `EvBattery.chargePortOpen = true`, `EvBattery.level = 63300.0`, `Odometer.rangeRemaining = 0.0`.

**B. 바뀌는 순간 앱이 알림을 받는지 (`PUSH`) — ✅ 수신 확인, ⚠️ 백그라운드에서는 지연**

1. **`ZZAN SDK Probe` 앱을 에뮬레이터 화면 맨 앞에 띄워 둡니다.** 앞에 없으면 `adb shell am start -n com.zzandrive.probe/.ProbeActivity` 로 띄울 수 있습니다.
2. 브라우저에서 **Battery SOC** 를 움직입니다.
3. 앱 화면 맨 아래(또는 `adb logcat -s ZzanProbe:I`)에 `PUSH EvBattery.SOC -> 값 %` 줄이 새로 생깁니다.
4. 프로브가 구독하는 값은 **SOC 하나뿐**입니다. 다른 슬라이더를 바꿔도 PUSH는 나오지 않습니다 (A 방식으로 다시 읽어야 함).

**실측 결과 (2026-11-08):** 프로브 앱이 **백그라운드**에 있을 때 SOC를 100→70→49로 바꾸면 그 순간에는 로그가 없다가, **앱을 앞으로 가져온 시점에 `PUSH -> 70`, `PUSH -> 49` 가 몇 ms 간격으로 한꺼번에** 찍혔습니다(03:10:26.074 / .075). 즉 **알림은 전달되지만 백그라운드에서는 앱에 도착하는 시점이 지연**됩니다. 원인은 Android가 백그라운드 앱의 실행을 멈추기 때문으로 **추정**하며(확정 아님), 설계상 "변화를 놓치지 않으려면 앞에 있거나 포그라운드 서비스로 동작해야 하는지"는 별도 확인이 필요합니다.

## 3. 정상일 때 보이는 결과

| 확인 항목 | 기대 결과 |
| --- | --- |
| `emulator -list-avds` | `Pleos_Connect_v2.1.0` 포함 |
| `adb devices` | `emulator-5554   device` |
| 에뮬레이터 차량 화면 | 왼쪽 상단 `500 km`, 기어 `P R N D` |
| `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8088/` | `200` |
| 브라우저 Virtual Console 하단 | `DDS ONLINE` (브라우저를 열어 둔 동안) |
| `scripts/probe.sh` 출력 | `OK EvBattery.SOC (%) = 100`, `OK Odometer.rangeRemaining = 500000.0` 등 |
| `Fuel.percent` | **`FAIL ... not supported` 가 정상**입니다 (전기차 이미지에 연료 항목 없음) |

## 4. 문제 해결

| 증상 | 원인·해결 |
| --- | --- |
| `adb devices` 에 아무것도 없음 | 에뮬레이터가 부팅 중이거나 꺼져 있음. `scripts/emulator.sh start` 후 다시 확인. 그래도 안 되면 `adb kill-server && adb start-server` |
| 8088 접속 안 됨 | `adb forward tcp:8088 tcp:8088` 재실행. 다른 프로그램이 8088을 쓰는지 확인(`lsof -i :8088`) |
| 값 변경 시 `emulator_inactive` | 브라우저에서 `http://127.0.0.1:8088/` 를 열어 둔 상태인지 확인 |
| 값을 바꿨는데 `PUSH` 가 안 나옴 | ① 프로브가 구독하는 것은 **SOC뿐**. ② 앱이 백그라운드이면 알림이 **지연**됨(앞으로 가져오면 한꺼번에 도착) → 앱을 앞에 띄우고 확인. ③ 그래도 안 오면 `adb logcat -s ZzanProbe:I` 에서 `register` 관련 오류 확인 |
| `curl` 로 값을 바꾸면 `emulator_inactive` | 정상 동작. 값 변경은 **브라우저에서** 해야 함 |
| `SecurityException: ... need Permission` | `AndroidManifest.xml` 에 해당 권한이 없음. 예) 배터리 용량은 `CAR_INFO`, TTS는 `TTS_SERVICE` 필요 |
| `need Permission pleos.car.permission.FUSED_LOCATION` (선언했는데도) | 런타임 권한이라 별도 부여가 필요. 에뮬레이터는 사용자 10이므로 `adb shell pm grant --user 10 com.zzandrive.probe pleos.car.permission.FUSED_LOCATION` |
| `property ID ... not supported` | 이 에뮬레이터 이미지가 지원하지 않는 항목(예: 연료). 코드 문제가 아님 → Mock 사용 |
| 앱 값이 비어 있거나 실패 | `Vehicle.initialize()` 직후에는 서비스 연결이 보장되지 않음. 약 2초 뒤에 호출 |
| 같은 AVD를 두 번 실행해 오류 | 먼저 `scripts/emulator.sh stop` |
| 에뮬레이터가 화면이 깨지거나 매우 느림 | 공식 요구사항은 외장 GPU(NVIDIA RTX 3060급 이상 등). Mac M1 Pro는 정상 동작 확인. 안 되면 Windows(NVIDIA) PC 사용 |
| 빌드 중 `SDK location not found` | `ANDROID_HOME` 미설정. 1-4 확인 후 **새 터미널**에서 실행 |

## 5. Windows 에서는 (⚠️ 미검증)

| 항목 | Mac | Windows |
| --- | --- | --- |
| SDK 경로 | `~/Library/Android/sdk` | `%LOCALAPPDATA%\Android\Sdk` |
| 환경변수 | `~/.zshrc` | 시스템 환경 변수에 `ANDROID_HOME`, `JAVA_HOME`(JDK 17), `Path`에 `platform-tools`, `emulator`, `cmdline-tools\latest\bin` 추가 |
| 업데이트 사이트 | `crp-arm64-sys-img.xml` | `crp-sys-img.xml` |
| GPU | Apple Silicon에서 동작 확인 | **NVIDIA RTX 3060급 이상 / AMD RX 6600M급 이상** 필요 |
| 스크립트 | `scripts/*.sh` | `.sh` 는 Git Bash/WSL에서만 동작. 아래 명령을 직접 입력 |
| Gradle | `./gradlew` | `gradlew.bat` |

`scripts/*.sh` 를 쓰지 않고 직접 입력하는 방법 (Mac/Windows 공통):

```bash
emulator -avd Pleos_Connect_v2.1.0          # 에뮬레이터 실행 (이 터미널이 점유됨, 새 터미널 사용)
adb wait-for-device
adb shell getprop sys.boot_completed        # 1 이면 부팅 완료
adb forward tcp:8088 tcp:8088               # 이후 브라우저로 http://127.0.0.1:8088/

cd tools/sdk-probe
./gradlew assembleDebug                     # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zzandrive.probe/.ProbeActivity
adb logcat -s ZzanProbe:I
```

## 6. 아직 알 수 없는 것

- Gleo AI: 앱이 있고 스키마를 읽으며 우리 Callback도 받지만, **Gleo 음성 엔진이 모델 데이터를 못 찾아 음성 명령은 현재 불가**. 자세한 내용과 문의 사항은 [gleo-interface.md](../references/gleo-interface.md)
- **백그라운드 앱이 차량 값 변화를 제때 받는 방법.** 포그라운드에서는 수신 확인, 백그라운드에서는 앱이 앞으로 올 때까지 지연됨. 충전·주유 감지(F02·F03)를 운전 중 항상 하려면 포그라운드 서비스 등이 필요한지 확인해야 함
- 연료·속도·위치 값을 에뮬레이터에 넣는 방법 (전기차 이미지는 연료 항목 미지원 → Mock 필요)
- TTS / STT / FusedLocation: Playground에서 "준비 중"으로 확인됨 (2026-11-08). 열리면 시험
- NaviHelper의 실제 경로 계산·통행료·경로 옵션 변경: 에뮬레이터에서는 반영되지 않음 ([결과](../references/api-feasibility.md))

자세한 조사 결과는 [docs/references/pleos-sdk.md](../references/pleos-sdk.md) 를 참고하세요.
