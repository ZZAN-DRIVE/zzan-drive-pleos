# data

차량·위치·외부 API 데이터 소스. 상위 계층은 인터페이스만 알고, 구현이 Mock인지 실제 SDK인지 알지 못한다.

| 폴더 | 책임 |
| --- | --- |
| `source-api/` | `VehicleSource`(차량 값 구독), `StationSource`(주유소·충전소와 단가), `PlaceSource`(저장한 장소), `RouteSource`(안내 중인 경로, 출발 전 경로 후보), `Clock`·`Scheduler` 인터페이스와 데이터 모델. 모든 값에 출처(`DataOrigin`)가 붙는다 |
| `source-mock/` | 연료·배터리 잔량, 충전구·충전 스위치, 정차·주행, 위치를 시계열로 재생하는 Mock(`Scenarios`, `ReplayVehicleSource`)과 예시 주유소·충전소·경로·저장 장소(`Fixture*`, 강릉길 주유소 5곳은 기획 계산식의 예시), 시연 C의 운전 습관 주행(`Scenarios.habitTrip`: 속도·엔진·제한속도). 에뮬레이터 센서가 부족할 때(spec V-02)의 대안 |
| `source-pleos/` | 실제 Pleos SDK 연동. `PleosVehicleSource`(Vehicle SDK·FusedLocation), `PleosNavigator`(NaviHelper) |

`source-mock`으로 만든 것은 spec.md 9.1 연동 매트릭스에서 🟡(Mock)로 표기한다.

`source-pleos`는 Pleos Playground Maven 저장소의 Vehicle 2.3.2(`vehicle-data` 1.1.11), NaviHelper 3.0.5, FusedLocation 1.1.3을 쓴다.

- `PleosVehicleSource`: 배터리 잔량(%)은 Vehicle SDK의 변화 알림으로 받고, 저장 에너지(Wh)·용량·충전구·충전 스위치·기어는 2초마다 읽는다. 위치는 FusedLocation으로 받으며 런타임 권한이 필요하다. 모든 값의 출처는 `PLEOS_SDK`다.
- Pleos Connect 에뮬레이터 2.1.0(전기차 이미지)은 연료 잔량(`Fuel.getFuelPercent`)을 지원하지 않는다. 그래서 주유 시나리오(시나리오 A)는 `source-mock`으로만 재생한다. 충전 시나리오(시나리오 B)는 실제 차량 데이터로도 돌릴 수 있다(`scripts/e2e-scenario-b.sh`).
- `PleosNavigator`: 승인한 장소를 목적지로 보내고, 안내 중이면 첫 경유지로 넣는다. 에뮬레이터 2.1.0에서는 내비 앱이 그 목적지까지의 경로를 계산해 보여 준다(바탕 지도는 그려지지 않는다).
- 실차 런타임도 주유소·충전소의 가격·거리와 저장 장소는 `source-mock`의 예시 데이터를 쓴다. 화면에는 "예시 데이터"로 표시한다.
