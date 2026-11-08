# Pleos Connect SDK 조사 결과

> 조사일: 2026-11-06. 출처: Pleos Playground 개발 문서(document.pleos.ai)와 공모전 안내 페이지.
> 문서가 바뀔 수 있으므로 구현 전에 원문을 다시 확인한다. "미확인"은 문서에서 찾지 못했다는 뜻이다.

## 1. 공모전·플랫폼 요건

| 항목 | 요건 |
| --- | --- |
| 최소 Android SDK | API 28 이상 (차량 사이버 보안 요건: 사이닝 키 로테이션, 리니지) |
| Gradle | 8.0 이상 |
| 언어 | Java 또는 Kotlin (**우리 프로젝트는 미확정**, spec D-13) |
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (Android 14 / API 34) |
| 제출 | 기획서 + 개인정보 동의서(필수), 시연 영상·GitHub(소스+APK)(가산점) |

## 2. SDK 구성과 사용 가능 상태

Playground 프로젝트 > SDK 메뉴 기준 (문서 "프로젝트 생성 및 SDK 사용 신청").

| SDK | 용도 | Playground 상태 | Maven 최신 | 비고 |
| --- | --- | --- | --- | --- |
| Vehicle SDK (State) | 차량 상태 조회 | **신청 없이 사용 가능** | 2.3.2 | 문서 기준 SDK v2.3.2 / Service v2.3.21~22 |
| NaviHelper SDK | 목적지·경유지·경로 옵션 | **신청 없이 사용 가능** | 3.0.5 | 요청은 Pleos Connect Navigation 앱으로 전달, 결과는 `NaviHelperEventListener` |
| FusedLocation SDK | 위치 | ⚠️ **준비 중 (Coming Soon)** | 1.1.3 | Maven에는 올라와 있으나 신청 불가 표시 |
| TextToSpeech SDK | 음성 합성 | ⚠️ **준비 중 (Coming Soon)** | 3.3.1 | 서버 연결형 → 클라이언트 ID/시크릿 필요 |
| SpeechToText SDK | 음성 인식 | ⚠️ **준비 중 (Coming Soon)** | 3.3.1 | 서버 연결형 → 클라이언트 ID/시크릿 필요 |
| Vehicle Control SDK | 차량 제어 | ⚠️ 준비 중 | — | 짠드라이브는 제어 불필요 |

→ TTS·STT·FusedLocation을 쓰려면 Playground에서 신청 가능 여부를 다시 확인해야 한다. 불가하면 Android 표준 API 대안을 쓴다 (spec V-06, V-13).

## 3. Maven 저장소와 의존성

| 항목 | 값 |
| --- | --- |
| 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` |
| 인증 | 불필요 (2026-11-06 기준 익명 HTTP 200 확인) |
| Vehicle | `ai.pleos.playground:Vehicle:2.3.2` |
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` |
| FusedLocation | `ai.pleos.playground:FusedLocation:1.1.3` |
| TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` |
| SpeechToText | `ai.pleos.playground:SpeechToText:3.3.1` |

`Vehicle:2.3.2`의 런타임 의존성: `vehicle-aidl:1.1.1`, `vehicle-data:1.1.11`, `core-ktx:1.8.0`, `kotlinx-coroutines-android:1.6.3`, `kotlin-reflect:1.9.25`, `gson:2.10.1` (compile: `kotlin-stdlib:1.9.25`).
> Vehicle SDK POM이 Kotlin 라이브러리에 의존하므로, Java로 구현해도 Kotlin stdlib가 함께 들어온다.

Gradle 설정 (공식 문서 그대로):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
// build.gradle.kts
dependencies { implementation("ai.pleos.playground:Vehicle:2.3.2") }
```

## 4. Vehicle SDK 사용법 요약

- 진입점 `Vehicle` → `initialize()`로 연결, `release()`로 해제. 도메인 객체는 `getXxx()`로 얻는다 (`getDoor()`, `getBrake()` …).
- 도메인 API 4종: **Capability**(`checkXXXCapabilities`), **Get**, **Set**, **Callback**(리스너).
- 차량과 IPC 통신이므로 결과는 `onComplete` / `onFailed` **비동기 람다**로 온다. 콜백이 끝나기 전에 `release()` 금지.
- `initialize()`가 예외 없이 반환돼도 Service 연결·기능 사용 가능을 보장하지 않는다 → 반드시 Capability 확인.
- **일반 Android 폰·기본 에뮬레이터에서는 차량 속성을 테스트할 수 없다.** Pleos Vehicle Service가 포함된 Pleos Connect AVD가 필요하다.

### 짠드라이브 관련 도메인

| 도메인 | 용도 | 퍼미션 |
| --- | --- | --- |
| `Fuel` | 연비 등 연료 정보 | `pleos.car.permission.CAR_ENERGY` |
| `EvBattery` | EV 배터리·충전 상태·전비 | `pleos.car.permission.CAR_ENERGY` |
| `Odometer` | 총·트립 주행 거리/시간 | `pleos.car.permission.CAR_DRIVE` |
| `CarInfo` | 차량 기본 정보 | `pleos.car.permission.CAR_INFO` |
| `DrivingMode` | 드라이브 모드 | `pleos.car.permission.CAR_DRIVE` |
| `Toll` | 하이패스(ETCS) 음성 안내 음량 등 | `pleos.car.permission.CAR_TOLL` |

- **연료 잔량(L)·차량 속도는 Vehicle SDK 도메인 목록에 없다.** 기획서대로 AAOS Car API(`FUEL_LEVEL`, `PERF_VEHICLE_SPEED`)로 읽어야 할 가능성이 높다 (SDK API 정책: 유사 기능은 AAOS를 우선).
- 세부 API마다 필요한 퍼미션이 다르므로 호출 전에 각 함수의 `@RequiresPermission`을 확인한다.
- **승인되지 않은 퍼미션을 선언하거나 해당 API를 호출하면 앱 심사 탈락.** 실제 쓰는 API에 필요한 **최소 퍼미션만** 선언한다.

## 5. 에뮬레이터 설치

| 항목 | 값 |
| --- | --- |
| Update Site (Mac arm64) | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` |
| Update Site (Win/Linux) | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` |
| 패키지 | `system-images;android-34;pleos_car_emulator;arm64-v8a` ("Pleos Connect v2.1.0 APIs arm64 System Image") |
| 크기 | 약 4.7GB (zip) |
| 라이선스 | **42dot Pleos Connect SDK License Agreement 동의 필요** (SDK는 Pleos Connect 호환 구현용 앱 개발에만 사용, 재배포·리버스 엔지니어링 금지, 42dot이 사전 고지 후 라이선스 비용을 요구할 수 있음) |

AVD 설정: Form Factor `Automotive` → `Pleos Connect v2.1.0` / API 34, **Default boot: Cold**, **RAM 4GB**. 이후 Pleos Connect Virtual Console 설정, 마이크 접근 설정이 필요하다.
프로젝트 ID(Key)는 앱 심사 요청 시 입력, 클라이언트 ID/시크릿은 TTS·STT에 사용.

## 6. 추가 조사 (2026-11-08)

### 6.1 에뮬레이터 하드웨어·설정

- **그래픽 카드(외장 GPU)가 필수**라고 명시. 권장: NVIDIA RTX 4060/5060 Laptop 8GB+, AMD RX 7700S/7600M XT급+. 최소: RTX 3060 Laptop 6GB+, RX 6600M 8GB+.
- 문서에는 **Apple Silicon/Mac 지원 여부 언급이 없다.** arm64 시스템 이미지는 제공되지만(`crp-arm64-sys-img.xml`) 공식 GPU 목록에는 NVIDIA·AMD만 있다 → M1 Pro에서 동작하는지는 **직접 부팅해 봐야 안다**.
- **Cold boot 필수**: quick boot이면 VHAL을 쓸 수 없다. RAM 4GB.
- 기본 언어는 한국어(차량 설정 > 일반 설정 > 언어에서 변경). 마이크는 Extended Controls > Microphone (STT 테스트용). Bluetooth 동글 미지원.
- Emulator v2.1.0은 2026-10-19 정식 릴리스(Playground v2.6.0 릴리스 노트도 같은 날 "LLM SDK 제공을 종료합니다. Gleo AI와의 연동이 필요한 경우 Gleo Interface를 이용할 수 있습니다."). **"LLM SDK 제공 종료"**, CRN 제공 종료, Bluetooth 동글 제공 종료.

### 6.2 Virtual Console (시뮬레이션 화면)

- 접속: 에뮬레이터 실행 후 `adb forward tcp:8088 tcp:8088` → 브라우저에서 `http://127.0.0.1:8088/`. 접속이 안 되면 `adb devices`, 8088 포트 충돌 확인. 최소 Emulator 2.1.0.
- 조작 가능 (Emulation Controls): Motion(기어, 브레이크, **Odometer**, 조향각), ADAS(크루즈 목표 속도), Energy(**Battery Energy / SOC / Capacity, Charge Switch, Charge Port**, Charge Current·Percent Limit, Remaining Range), ETCS 시나리오(카드 삽입/제거, 요금소 진입/진출), 도어·창문·조명·공조 등.
- **카드가 없는 항목: 연료(Fuel) 잔량, 현재 차량 속도, 위치(GPS).** 시나리오 만들기·재생·저장도 문서에 설명이 없다.
- 영향: 전기차 충전 감지(F03)는 Virtual Console로 시험 가능, **주유 감지(F02)와 급가속 감지(F07)는 Virtual Console만으로 불가** → Mock 데이터 소스 또는 AAOS 표준 방식(예: `adb shell cmd car_service inject-vhal-event`)의 가능 여부를 에뮬레이터에서 확인해야 한다 (미검증).

### 6.3 Gleo AI

> 더 자세한 조사 결과와 스키마 초안은 [gleo-interface.md](gleo-interface.md)에 있다. 아래는 1차 조사 요약.

- **Gleo AI는 앱이 호출하는 LLM SDK가 아니다.** 차량 음성 비서이며, 사용자 음성 명령을 function calling으로 처리해 **Gleo가 앱의 function을 호출**한다 (Gleo Interface). 앱이 Gleo에 질문해 답을 받는 API는 문서에서 확인되지 않는다.
- 연동 방식: 앱 `assets/schema.json`(App Schema)에 `actionCalls`(**최대 3개**)를 정의 → Gleo가 `startActivity()`(component=`activity`) 또는 `sendBroadcast()`(component=`service`)로 호출 → 앱이 Callback으로 응답 → Gleo가 답변 생성. AndroidManifest에 function 이름과 같은 action의 intent-filter 필요.
- 규칙: function `name`은 대문자+밑줄(앱 내 유일), `description`은 **영어**, 한두 문장, 예시·Gleo 동작 조작 문구 금지. 파라미터 type은 단일(string/number/boolean/array), enum은 문자열만.
- 승인: **Gleo 측이 스키마를 검토·승인해야 통합이 활성화된다.** 제출 경로(이메일/콘솔)와 **에뮬레이터 테스트 방법은 문서에 없다** (문서 저자에게 문의하라고 안내). 스키마는 부팅 시 1회, 그리고 ADB/앱마켓 설치·삭제 시 읽힌다.
- 짠드라이브 영향: 기획서의 "LLM SDK (Gleo AI)로 캐릭터가 자연스럽게 답변"(F05·F09)은 **현재 문서 기준으로 불가능**하다. 대신 "Gleo가 짠드라이브 기능을 호출"하는 방향(예: `FIND_CHEAPEST_STATION`, 최대 3개)은 가능성이 있으나 승인 절차를 거쳐야 한다.

### 6.4 실측 검증 결과 (2026-11-08, M1 Pro / macOS)

`scripts/emulator.sh start` + `scripts/probe.sh`(tools/sdk-probe, Vehicle 2.3.2)로 확인한 사실. 문서 추정이 아니라 **에뮬레이터에서 실제로 돌려 본 결과**다.

| 항목 | 결과 |
| --- | --- |
| Apple Silicon(M1 Pro)에서 에뮬레이터 부팅 | ✅ 약 25초, GPU 렌더러 `Apple M1 Pro`(Metal). 터미널만으로 실행 가능(Android Studio 불필요) |
| Gradle 빌드 | ✅ Gradle 8.10.2 / AGP 8.8.2 / Kotlin 2.0.0 / JDK 17, Maven 저장소 익명 접근으로 Vehicle 2.3.2 해석 |
| `Vehicle.initialize()` 후 약 2초 대기 → 호출 | ✅ 응답함 (initialize 직후 호출은 연결 보장 안 됨) |
| `EvBattery` SOC / level / chargeSwitch / chargePort | ✅ `CAR_ENERGY`만으로 동작 (SOC 100, level 77400.0, 스위치·포트 false) |
| `EvBattery.getEvBatteryCapacity` | ✅ **`CAR_INFO` 권한이 추가로 필요** (없으면 `SecurityException: need Permission`) |
| `Odometer` distanceDriven / rangeRemaining | ✅ `CAR_DRIVE` (distanceDriven 0.0, rangeRemaining 500000.0 = 500km, 단위 m) |
| Virtual Console에서 값 변경 → SDK 재조회 | ✅ Charge Port Open→`chargePortOpen=true`, Battery Energy 63.3→`level=63300.0`, Capacity 69.7→`capacity=69700.0`, Remaining Range 0→`rangeRemaining=0.0` 이 앱에서 읽힘. 변경은 **브라우저가 열려 있을 때만** 가능(`DDS ONLINE`) |
| SOC 변화 리스너(`registerEvBatteryStateOfCharge`) | ✅ **수신 확인.** 단 앱이 백그라운드이면 지연: SOC 100→70→49 변경분이 앱을 앞으로 가져온 시점(03:10:26)에 `PUSH -> 70`, `PUSH -> 49`로 한꺼번에 도착. 백그라운드 동작(포그라운드 서비스 필요 여부)은 미확인. 차량 내장 UI의 반응은 우리 코드의 증거가 아님 |
| **`Fuel.getFuelPercent`** | ❌ **전기차 이미지에서 미지원** (`property ID ... not in carPropertyConfig list: 0x21400be5`). 주유 감지(F02)는 이 에뮬레이터에서 SDK로 시험 불가 → Mock 필요 (V-02, V-12) |

- SDK 콜백 파라미터는 **null 허용 타입**(`Int?`, `Float?`)이다. 값이 없을 수 있다는 전제로 다뤄야 한다.
- 주행 거리 단위: Virtual Console 카탈로그는 Odometer `km`, Remaining Range `m` 표기. SDK는 Range를 m 단위로 반환한다.
- **Virtual Console 쓰기 활성화 조건:** `/vehicle/state`가 `browser.active=false`, `bridge_ready=false`("DDS OFFLINE")이면 쓰기 요청이 `{"error":"emulator_inactive"}`로 거부된다. 브라우저에서 `http://127.0.0.1:8088/`를 열어 세션이 잡혀야 하는 것으로 보인다(브라우저 세션 하트비트, 미확정).
- Virtual Console 서버 API(참고): `GET /vehicle/catalog`(기능·쓰기 엔드포인트 목록), `GET /vehicle/state`, `PUT /vehicle/ev-battery?field=soc&value=73` 형태. `/vehicle/state`의 `controls`에 `speed_kph`(0–240), `accelerator_pct`, `brake_pct`가 있어 6.2의 "현재 속도 카드 없음"은 카탈로그 기준이며, 속도 주입 가능 여부는 **재확인 필요**.

## 7. 참고 링크

- 개요: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- 개발 환경 구성: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- 프로젝트 생성·SDK 신청: https://document.pleos.ai/docs/pleos-only/create-my-project
- Vehicle SDK: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- Vehicle 퍼미션: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- NaviHelper SDK: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
- 공식 샘플 앱(Kotlin, 라이선스 표기 없음): https://github.com/Pleos-Playground/pleos-sample-vehicle-app
- Playground: https://pleos.ai/playground/discover/resources
