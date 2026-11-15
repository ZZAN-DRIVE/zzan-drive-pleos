# core

차량·SDK·화면에 의존하지 않는 핵심 로직이다. spec.md 4장의 계층 구조를 따른다. 순수 Kotlin(JVM) 모듈이라 에뮬레이터 없이 몇 초 안에 테스트가 끝난다.

```bash
./gradlew :core:test      # spec.md 6·7장 테스트 벡터
```

| 패키지 | 책임 | spec 참조 |
| --- | --- | --- |
| `domain` | `CostCalculator`(실질 주유·충전 비용, 목적지가 있을 때 주유소 경로의 총 지출, 경로 운행비, 시간가치 비교값), `Recommender`(후보 비교, 추천 시점 스냅샷), `Plan.kt`의 규칙(`TriggerPolicy` 알림 기준 %, `FillPolicy` 넣을 양, `CandidatePolicy` 도로 5 km, `Reachability` 주행 가능 거리와 목적지 분기, `RoutePolicy` 3곳 표시와 "주유 없이 가기", `CompareMode`·`CreditRule` 비교 방식과 적립 기준), `RoutePlanner`(출발 전 경로 비교), `LevelTable`·`CharacterProgress`(레벨 상태 머신), `CelebrationPolicy`(정차 중에만 연출), `PlanReference`(조사값·시연 가정) | F02~F04, F06, 7장, D-22~D-27, D-29~D-34 |
| `dialog` | `StationDialog`(질문 순서: 기준 위치 → 넣을 양 → 추천. 순수 상태 기계), `AnswerParser`(음성 답 해석. 동의는 답 전체가 동의 표현일 때만), `FillChoices`(넣을 양 칩과 debug 명령 id) | F05, D-23, D-25 |
| `settlement` | `LedgerEvent`(JSONL로 저장하는 정산 이벤트), `Ledger`(멱등 키·월간 손익·짠포인트), `SettlementPolicy`(승인·장소·12시간 조건, 실질비용 차이로 확정), `OpportunityCost`(기회비용 금액) | F08, 8장, D-02, D-21, D-28, D-29 |
| `verification` | `RefillDetector`(충전·주유 감지 상태기계), `StationMatcher`(300 m), `PassDetector`(추천 장소를 들르지 않고 지나침: 300 m 안 → 500 m 밖), `InefficiencyRules`(3-5 판정·비용, 급가속 건당·과속 비용), `DrivingMonitor`(속도·기어·엔진 표본을 주행 단위의 차감 내역으로) | F02~F04, F07, D-05, D-28, D-35~D-38 |
| `voice` | `WonFormat`(화면은 1원, 음성은 반올림 규칙), `VoiceLines`(짠돌이·짠순이 문구) | F05, D-12 |
| `motion` | `CharacterClips`(파일 이름으로 클립 역할과 그려진 번개 단계를 정하고, 레벨·레벨업·탭·정산 반응에 맞는 클립을 고른다), `BoltGauge`(머리 위 번개가 벗어나지 않는 상자와 레벨별 채움선. 디자이너 파일에서 잰 값), `HaloGauge`(짠순이 머리 위 고리의 상자) | 7.1, D-18, D-40, D-42 |

규칙:

- `core`는 `data`의 **인터페이스(`:data:source-api`)** 에만 의존하고, Pleos SDK·Android API를 직접 부르지 않는다. 이렇게 두어야 에뮬레이터 없이 단위 테스트를 돌릴 수 있다.
- 금액은 `BigDecimal`로 계산하고 확정할 때 한 번만 원 단위로 반올림한다.
- 모든 값에는 출처(`DataOrigin`: 차량 실측, 시연 데이터, 조사값 등)가 붙는다. 화면은 이 출처를 그대로 보여준다.
