# sdk-probe

Pleos Vehicle SDK가 에뮬레이터에서 실제로 응답하는지 확인하는 **버리는 프로브 앱**입니다.
제품 코드가 아니며, 제품 언어(spec.md D-13)와 무관하게 Kotlin으로 작성했습니다.

## 실행

```bash
scripts/emulator.sh start   # 에뮬레이터 부팅 (Android Studio 불필요)
scripts/probe.sh            # 빌드 -> 설치 -> 실행 -> logcat 출력
```

결과는 화면과 logcat(태그 `ZzanProbe`)에 같이 나옵니다.

## 확인하는 항목

| 호출 | 필요 권한 |
| --- | --- |
| `EvBattery` SOC / level / chargeSwitch / chargePort | `CAR_ENERGY` |
| `EvBattery.getEvBatteryCapacity` | `CAR_INFO` |
| `Odometer` distanceDriven / rangeRemaining | `CAR_DRIVE` |
| `Fuel.getFuelPercent` | `CAR_ENERGY` (전기차 에뮬레이터 이미지에서는 미지원) |

- SOC·연료 변화 리스너를 등록하므로, Virtual Console에서 값을 바꾸면 `PUSH` 로그가 나옵니다.
- 최소 권한 원칙: 프로브가 선언한 권한은 위 표의 3개뿐입니다.
