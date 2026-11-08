# SDK API 사용 가능성 테스트 결과

짠드라이브가 쓰려는 Pleos SDK 기능이 **에뮬레이터에서 실제로 불러와지고 쓸 수 있는지** 직접 호출해 확인한 기록입니다. 문서를 읽고 추정한 내용이 아니라, 아래 "재현 방법"의 명령으로 얻은 결과만 적습니다.

- 시험일: 2026-11-08
- 환경: Mac(M1 Pro) / Pleos Connect Emulator 2.1.0 (Android 14, API 34, 전기차 이미지) / Vehicle SDK 2.3.2 / NaviHelper SDK 3.0.5 / FusedLocation SDK 1.1.3 / TextToSpeech SDK 3.3.1
- 시험 코드: [tools/sdk-probe](../../tools/sdk-probe/) (버리는 프로브 앱, 제품 코드 아님)
- 범위: Playground에서 **"사용 가능"** 인 **Vehicle State**, **NaviHelper**, 그리고 **"준비 중(Coming Soon)"** 으로 표시된 **FusedLocation**, **TextToSpeech**, **Vehicle Control**(제어 API). SpeechToText는 마이크가 필요해 시험하지 않음.
- **"Coming Soon"의 의미:** 공식 문서상 "API 제공 준비 중인 상태로 **신청 버튼이 나타나지 않습니다**"이며, 기술적으로 막혀 있다는 뜻이 아닙니다. 실제로 아래 7장처럼 에뮬레이터에서 일부는 동작합니다. 다만 **앱 심사·배포 시 허용되는지는 확인하지 못했습니다.**

> **주의:** 이 결과는 **에뮬레이터** 기준입니다. 에뮬레이터가 값을 흉내 내지 못하는 항목은 "SDK가 안 된다"가 아니라 "여기서는 확인 못 한다"일 수 있습니다. 판정 칸에 이를 구분해 적었습니다.

## 1. 한눈에 보기

| 짠드라이브 용도 | 호출한 API | 판정 | 요약 |
| --- | --- | :-: | --- |
| 충전 감지 (F03) | `EvBattery` SOC·용량·충전스위치·충전구 + SOC 변화 알림 | ✅ | 읽기 정상, 변화 알림 수신 정상(백그라운드에서는 지연) |
| 주행거리·실측 연비 (F01) | `Odometer` 주행거리·남은 주행거리 | ✅ | 읽기 정상 |
| **주유 감지 (F02)** | `Fuel.getFuelPercent` | ❌ | 이 **전기차 이미지가 미지원**. 연료 이미지에서 되는지는 모름 |
| 정차·주행 판별 (D-15) | `Gear` 기어, `Brake` 브레이크 | ✅ | 읽기 정상 (`GEAR_PARK`, 브레이크 `false`) |
| 주행 모드 | `DrivingMode.getCurrentDrivingModeState` | ❌ | 에뮬레이터 미지원 |
| 차종 정보 (D-14) | `CarInfo.getVINInfo` / `getCapabilityInfo` | ⚠️ | VIN은 읽힘. `getCapabilityInfo`는 **권한 부족**(`SecurityException`), 필요한 권한은 미확인 |
| **급가속·속도 (F07)** | Vehicle SDK의 속도·가속도 조회 | ❌ | **SDK에 해당 API가 없음** (크루즈 목표 속도만 있음) |
| 현재 위치 | NaviHelper `getCurrentLocationInfo` | ✅ | 위도·경도·주소 수신 (에뮬레이터 고정 위치) |
| 내비 상태 | NaviHelper `getRouteStateInfo`, `onNavigationStatus` | ✅ | 안내 중/비안내 상태 변화를 이벤트로 수신 |
| 목적지 설정·취소 | NaviHelper `requestRoute`, `cancelRoute` | ✅ | 호출·이벤트(`onRouteStarted`, `onRouteCancelled`) 정상 |
| **경로 비용 (F01·F04)** | NaviHelper `onRouteStarted`/`getDestinationInfo`의 거리·시간·통행료 | ⚠️ | 값은 오지만 **고정값처럼 보임**(거리 50, 시간 60, 통행료 0). 실제 경로 계산 여부는 에뮬레이터에서 확인 불가 |
| 경로 옵션 비교 (F04) | NaviHelper `changeRouteOption(FREE / SHORT_TIME)` | ❌ | 호출은 오류 없이 되지만 **변경이 반영되지 않음** (이벤트 없음, 옵션·값 그대로) |
| **현재 위치·근접 알림** (주유소 근처 판단) | FusedLocation `getLastKnownLocation`, `registerFusedLocationCallback`, `addProximityAlert` | ✅ | 위치가 초당 1회 수신, 근접 알림 등록 성공. **런타임 권한 부여가 필요** (7장). 위치값은 에뮬레이터 기본값(미국 구글 본사) |
| 음성 안내 (F05) | TextToSpeech `ON_DEVICE` / `SERVER` | ⚠️ | SDK 호출·권한은 통과, **소리 합성은 실패** (`ON_DEVICE_ENGINE_ERROR`). 서버 모드는 클라이언트 등록 후 시험 필요 |
| 차량 제어 (Vehicle Control) | `EvBattery.setEvChargePortOpen` 등 set API | ✅ | `CONTROL_CAR_ENERGY` 권한으로 동작 (현재와 같은 값을 쓰는 방식으로만 시험) |

## 2. Vehicle State 상세

호출 방식은 `Vehicle.initialize()` → 약 2초 대기 → 도메인 API 호출입니다. 권한은 `CAR_ENERGY`, `CAR_DRIVE`, `CAR_INFO` 3개만 선언했습니다.

| API | 필요 권한 | 결과 |
| --- | --- | --- |
| `EvBattery.getEvBatteryStateOfCharge` | `CAR_ENERGY` | `49` (Virtual Console에서 바꾼 값 그대로) |
| `EvBattery.getEvBatteryLevel` | `CAR_ENERGY` | `63300.0` (Virtual Console 63.3 kWh와 일치, 단위 Wh) |
| `EvBattery.getEvBatteryCapacity` | **`CAR_INFO`** | `69700.0` (권한 없으면 `SecurityException`) |
| `EvBattery.getEvChargeSwitchState` / `isEvChargePortOpen` | `CAR_ENERGY` | `false` / `true` |
| `Odometer.getCurrentDistanceDriven` | `CAR_DRIVE` | `0.0` |
| `Odometer.getRangeRemainingDistance` | `CAR_DRIVE` | `0.0` (단위 m) |
| `Fuel.getFuelPercent` | `CAR_ENERGY` | **실패**: `property ID is not in carPropertyConfig list ... 0x21400be5` |
| `Gear.getCurrentGearSelectionState` | `CAR_DRIVE`(추정) | `GEAR_PARK` |
| `Brake.getCurrentBrakePedalStatus` | `CAR_DRIVE`(추정) | `false` |
| `DrivingMode.getCurrentDrivingModeState` | — | **실패**: `property ID ... not supported: 0x21400410` |
| `CarInfo.getVINInfo` | `CAR_INFO` | 길이 9 문자열 (에뮬레이터용 값으로 추정, 내용은 기록하지 않음) |
| `CarInfo.getCapabilityInfo` | **미확인** | **실패**: `SecurityException: getCapabilityInfo need Permission` |
| SOC 변화 알림 `registerEvBatteryStateOfCharge` | `CAR_ENERGY` | 수신 확인. **앱이 백그라운드면 지연**: 100→70→49 변경분이 앱을 앞으로 가져온 시점에 한꺼번에 도착 |

발견한 사항:

1. **Vehicle SDK에는 차량 속도·가속도 조회가 없습니다.** 도메인 목록은 `Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, Gear, HVAC, Light, Odometer, Safety, Seat, SideMirror, SteeringWheel, Tire, Toll, TurnSignal, Window, Wiper` 이고, 속도 관련은 `DrivingMode`의 크루즈 목표 속도뿐입니다.
2. SDK 의존성 함정: `Vehicle` POM이 `vehicle-data`를 **runtime 범위**로만 선언해서, `CarInfo.getCapabilityInfo`처럼 `CapabilityConfig`를 쓰는 코드는 **컴파일 시 클래스를 못 찾습니다.** `implementation("ai.pleos.playground:vehicle-data:1.1.11")`을 직접 추가하면 해결됩니다.
3. SDK 콜백 값은 모두 **null 허용 타입**(`Int?`, `Float?`)입니다.

## 3. NaviHelper 상세

호출 순서: `NaviHelper(context)` → `initialize(onSuccess, onFailure)` → `addListener()` → 요청 → 이벤트 콜백 → `removeListener()` / `release()`. 정보 조회 API(`get*Info()`)는 값을 반환하지 않고 **이벤트 콜백으로 결과를 줍니다.** 선언한 권한은 `NAVI_ROUTE`(조회), `NAVI_ROUTE_SEARCH`(경로 요청·변경·취소)입니다.

시나리오와 실제 수신 로그(발췌):

```
initialize() onSuccess
getCurrentLocationInfo   -> onCurrentLocationInfo lat=37.4127212 lon=127.094681 addr=수정구 시흥동
getRouteStateInfo        -> onRouteStateInfo routing=false mode=SAFE_GUIDING option=RECOMMENDED terms=true
requestRoute(42dot POI)  -> onRouteStarted name=42dot distance=50 duration=60 tollFare=0
                            onNavigationStatus ROUTE_DRIVE
getDestinationInfo       -> onDestinationInfo name=42dot remainDistance=50 remainTime=60 tollFare=0
changeRouteOption(FREE)  -> (이벤트 없음)  이후 조회: option=RECOMMENDED, distance=50, time=60, toll=0
changeRouteOption(SHORT_TIME) -> (이벤트 없음)  이후 조회: 값 동일
cancelRoute              -> onRouteCancelled, onNavigationStatus FREE_DRIVE
```

해석 (확인된 것과 추정을 구분):

- **확인:** 초기화, 위치 조회, 상태 조회, 목적지 설정·취소, 상태 이벤트는 동작합니다. `RouteStartInfo`·`DestinationInfo`에 `tollFare`, 거리, 시간 필드가 있어 **실제 내비에서는 통행료를 줄 가능성**이 있습니다.
- **확인:** 에뮬레이터 현재 위치(성남 수정구)에서 강남 목적지까지 실제 거리는 수 km 이상인데 `distance=50`, `duration=60`이 나왔고, 옵션을 바꿔도 같은 값입니다. 에뮬레이터의 내비 엔진은 **실제 경로를 계산하지 않는 것으로 보입니다.** (단위는 문서에 없어 모릅니다. 값이 고정이므로 단위를 추정할 근거도 없습니다.)
- **확인:** `changeRouteOption`은 활성 경로가 있는 상태에서도 반영되지 않았습니다. 첫 시도에서 경로 시작 전에 호출한 경우를 배제하려고 경로 시작을 기다린 뒤 다시 시험했고 결과는 같았습니다.
- **미확인:** 실제 차량·실제 내비에서 `changeRouteOption`과 `tollFare`가 동작하는지는 이 환경에서는 알 수 없습니다.
- 위치는 에뮬레이터 기본값으로 보이며, 바꾸는 방법은 아직 확인하지 못했습니다.

## 4. 설계에 주는 영향

| 항목 | 영향 | 제안 (결정은 spec.md D-xx) |
| --- | --- | --- |
| 주유 감지(F02) | 전기차 이미지에서는 연료 값을 얻을 수 없음 | 주유는 Mock `VehicleDataSource`로 시연. 연료 이미지·AAOS `FUEL_LEVEL` 경로는 별도 시험 필요 (V-02, V-12) |
| 급가속·속도(F07) | Vehicle SDK로는 속도·가속도 불가 | AAOS `PERF_VEHICLE_SPEED` 시험, 안 되면 Mock (V-12) |
| 경로 비용·비교(F04) | 에뮬레이터 내비는 거리·통행료·옵션 비교가 되지 않음 | **spec.md 방침대로 외부 경로 API로 후보 비교**, NaviHelper는 목적지·경유지 전달과 안내 상태 확인에 사용 (V-04) |
| 충전 감지(F03) | SOC 읽기·변화 알림은 가능 | 백그라운드 지연 때문에 포그라운드 서비스 등 동작 방식 확인 필요 |
| 정차 판별(D-15) | `Gear`로 P/N 판별 가능 | 속도는 SDK에 없으므로 기어 + 별도 속도 소스 |
| 차종 기본값(D-14) | `getCapabilityInfo`는 현재 쓸 수 없음 | `CarInfo`는 VIN 외에는 기대하지 않고, 차종별 기본값은 자체 설정 데이터로 |

## 5. 재현 방법

```bash
scripts/emulator.sh start            # 에뮬레이터 실행
scripts/probe.sh vehicle             # Vehicle State 시험 (약 8초)
scripts/probe.sh navi                # NaviHelper 시험 (약 50초, 10단계 시나리오)
scripts/probe.sh loc                 # FusedLocation 시험 (먼저 7장의 권한 부여 필요)
scripts/probe.sh tts                 # TextToSpeech 시험 (약 28초)
scripts/probe.sh control             # Vehicle 제어 API 시험 (상태를 바꾸지 않는 방식)
```

결과는 터미널과 에뮬레이터의 `ZZAN SDK Probe` 화면, `adb logcat -s ZzanProbe:I`에 같은 내용으로 나옵니다. 값을 바꿔 보려면 [환경 세팅 문서](../setup/environment.md)의 Virtual Console 절차를 따릅니다.

## 6. 시험하지 않았거나 모르는 것

- SpeechToText: 마이크가 필요해 시험하지 않음.
- TextToSpeech `SERVER` 모드의 앱 등록(`registerApp`): 클라이언트 ID/시크릿이 필요한 단계라 시험하지 않음. 시크릿을 코드·로그에 남기지 않는 방식을 정한 뒤 진행해야 함.
- 클라이언트 ID/시크릿은 **Vehicle, NaviHelper, FusedLocation에는 필요하지 않았음** (코드에도 넣지 않음).
- Gleo AI: 에뮬레이터에 Gleo AI 앱이 있고 우리 스키마를 읽는 것까지 확인. 실제 호출은 미확인 ([gleo-interface.md](gleo-interface.md)).
- 연료 이미지, 실차, 위치를 바꿔 가며 하는 NaviHelper 시험.
- `CarInfo.getCapabilityInfo`에 필요한 권한.
- "Coming Soon" SDK가 **Pleos 앱 마켓 심사·배포 때 사용 허용되는지** (문서에 없음, Pleos 문의 필요).
- FusedLocation 위치를 바꾸는 방법, 앱 안에서 런타임 권한을 요청하는 흐름.

## 7. "Coming Soon" SDK 시험 상세와 권한 함정

### 7.1 FusedLocation 1.1.3

- 호출 순서: `FusedLocation(context)` → `isSupported()` → `initialize()` → `getLastKnownLocation` / `registerFusedLocationCallback` / `addProximityAlert`.
- 결과: `isSupported() = true`, 위치 콜백이 **초당 1회**, 근접 알림 `onRegistered` 성공. 위치는 `37.4219983, -122.084`(에뮬레이터 기본값, 미국 구글 본사). 같은 시각 NaviHelper가 준 현재 위치(성남 수정구)와 **다름** → 두 SDK의 위치 소스가 서로 다른 것으로 보임.
- **함정 1: `pleos.car.permission.FUSED_LOCATION`은 런타임(dangerous) 권한입니다.** 매니페스트에 선언만 하면 `SecurityException: need Permission pleos.car.permission.FUSED_LOCATION`이 납니다. 실제 앱은 런타임 요청이 필요하고, 시험에서는 adb로 부여했습니다.
- **함정 2: 에뮬레이터는 앱이 사용자 10(운전자 사용자)으로 실행됩니다.** `adb shell pm grant`는 기본이 사용자 0이라 **효과가 없었고**, `--user 10`을 붙여야 했습니다.

```bash
adb shell pm grant --user 10 com.zzandrive.probe pleos.car.permission.FUSED_LOCATION
```

- `FusedLocationListener.onChanged`의 파라미터는 null 허용(`LocationData?`)입니다.

### 7.2 TextToSpeech 3.3.1

- 필요 권한: `pleos.car.permission.TTS_SERVICE`(normal, 선언만 하면 됨). 없으면 `SecurityException: Permission ... TTS_SERVICE is required`.
- **호출 순서가 중요:** `initialize()` → (성공 콜백 안에서) `addEventListener()` 순서여야 합니다. 리스너를 먼저 등록하면 `UninitializedPropertyAccessException: lateinit property tts has not been initialized`가 납니다.
- `ON_DEVICE`: 초기화 `onSuccess` → `onReady` → `onStart` → **`onError ON_DEVICE_ENGINE_ERROR`**. 문서는 이 모드가 앱 등록 없이 쓸 수 있다고 하지만, **이 에뮬레이터에서는 합성에 실패**했습니다(온디바이스 모델 파일이 없는 것으로 추정, 미확인).
- `SERVER`(앱 등록 전): 초기화는 성공하고 `speak` 후 `onError UNKNOWN`. 등록(`registerApp`) 이후 동작은 시험하지 않았습니다.
- 에뮬레이터에는 음성 서비스(`ai.pleos.playground.caas`)가 설치돼 있습니다.

### 7.3 Vehicle Control (제어 API)

- `EvBattery.checkEvChargeSwitchCapabilities` / `checkEvChargePortOpenCapabilities`의 `accessType`이 모두 `READ_WRITE`.
- `isEvChargePortOpen`으로 현재 값(`true`)을 읽고 **같은 값으로** `setEvChargePortOpen`을 호출 → `onComplete result=true`. 차량 상태는 바뀌지 않았습니다.
- 필요 권한: `CONTROL_CAR_ENERGY`(normal). 즉 포털의 "Vehicle Control: Coming Soon"과 별개로 Vehicle SDK의 set API는 이 에뮬레이터에서 동작합니다.
