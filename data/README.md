# data

차량·위치·외부 API 데이터 소스. 상위 계층은 인터페이스만 알고, 구현이 Mock인지 실제 SDK인지 알지 못한다.

| 폴더 | 책임 |
| --- | --- |
| `source-api/` | `VehicleDataSource`, `LocationSource`, `PriceSource`, `RouteSource` 등 인터페이스 |
| `source-mock/` | 연료·배터리·속도 시계열을 재생하는 Mock. 에뮬레이터 센서가 부족할 때(spec V-02)의 대안 |
| `source-pleos/` | 실제 Pleos SDK / AAOS 연동 구현 |

`source-mock`으로 만든 것은 spec.md 9.1 연동 매트릭스에서 🟡(Mock)로 표기한다.
Pleos SDK 확인 결과는 [docs/references/pleos-sdk.md](../docs/references/pleos-sdk.md)를 참고한다.
