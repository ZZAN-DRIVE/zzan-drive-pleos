# core

차량·SDK·화면에 의존하지 않는 핵심 로직. spec.md 4장의 계층 구조를 따른다.

| 폴더 | 책임 | spec 참조 |
| --- | --- | --- |
| `domain/` | 모델, 단위, `CostCalculator`(실질 주유·충전 비용, 경로 총비용), `CharacterProgress`(레벨 상태 머신), 레벨표 설정 | F02~F04, F06, 7장 |
| `settlement/` | 정산 원장, 확정 절약·확정 차감, 월간 손익, 중복 방지 | F08, 8장 |
| `verification/` | 주유·충전 감지, 경로 완주 확인, 비효율 주행 감지 | F02~F04, F07 |

규칙: `core`는 `data`의 **인터페이스(`source-api`)** 에만 의존하고, Pleos SDK·Android API를 직접 부르지 않는다.
이렇게 두어야 에뮬레이터 없이 단위 테스트(spec 6·7장 테스트 벡터)를 돌릴 수 있다.
