# Gleo AI 연동 (Gleo Interface) 조사 결과

짠드라이브가 **Gleo AI(차량 음성 비서)와 어떻게 연동할 수 있는지** 공식 문서와 에뮬레이터 확인으로 정리한 문서입니다. 각 문장에 근거를 구분해 적었습니다.

- 📖 **문서 확인:** Pleos 공식 문서에서 읽은 내용
- 🔬 **에뮬레이터 확인:** 2026-11-08 에뮬레이터에서 직접 확인한 내용
- ❓ **미확인:** 문서에도 없고 확인도 못 한 내용

## 1. 결론

| 질문 | 답 |
| --- | --- |
| LLM SDK로 우리 앱에서 Gleo에게 질문하고 답을 받을 수 있나 | ❌ 📖 **LLM SDK는 제공이 종료**되었습니다 (Playground v2.6.0, 2026-10-19). 대체 수단으로 Gleo Interface를 안내합니다 |
| 그럼 Gleo AI는 어떻게 연동하나 | 📖 **Gleo가 우리 앱의 기능(function)을 호출**하는 방식입니다. 앱이 먼저 Gleo를 호출하는 API는 문서에서 확인되지 않습니다 |
| 기획서의 "캐릭터가 LLM으로 자연스럽게 대화"(F05·F09)는 | ❌ 현재 문서 기준 불가. 대신 "음성 명령 → Gleo → 우리 앱 기능 실행 → 결과를 Gleo가 말해 줌" 형태가 가능 |
| 지금 에뮬레이터에서 시험할 수 있나 | ❌ 🔬 에뮬레이터에 **Gleo AI 앱 본체가 없어서** 끝까지 시험할 수 없습니다 (3장) |
| 우리가 연동을 받으려면 | 스키마를 만들어 **Gleo 측 검토·승인**을 받아야 합니다. 제출 경로는 문서에 없습니다 (5장) |

## 2. 문서에서 확인한 내용 📖

출처: [릴리스 노트](https://document.pleos.ai/docs/pleos-only/release-note/playground-release-note), [Core Concepts](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [App Capability](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [Action & Callback](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback), [Examples](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [Deliver Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)

### 2.1 LLM SDK 종료

릴리스 노트 v2.6.0(2026-10-19) 제거 항목: "LLM SDK 제공을 종료합니다. Gleo AI와의 연동이 필요한 경우 Gleo Interface를 이용할 수 있습니다." 별도 종료일·대체 일정은 적혀 있지 않습니다.

### 2.2 전체 흐름

```
사용자 음성 → Gleo AI ──(1) Gleo Action: Intent로 앱 호출──▶ 우리 앱
                  ▲                                         │
                  └──(2) Callback: 브로드캐스트로 결과 응답───┘
Gleo AI가 결과를 보고 사용자에게 최종 답변(음성/텍스트) 생성
```

- 기반은 function calling입니다. 개발자는 **App Schema**(JSON)에 Gleo가 호출할 수 있는 function을 등록합니다.
- 사용자는 여전히 화면으로 앱의 다른 기능을 쓸 수 있습니다. function은 "음성으로 시킬 수 있는 일"의 목록입니다.

### 2.3 App Schema (`assets/schema.json`)

- 파일명은 반드시 `schema.json`, 위치는 앱의 `assets/schema.json`.
- 구조: `packageName`, `appDescription`, `version`(**Pleos 앱 마켓에 등록된 버전**), `actionCalls`(**최대 3개**). 각 항목은 `type: "function"`, `function`{`component`(`activity` 기본 / `service`), `name`, `description`, `parameters`}.
- `component`: `activity` → `startActivity()`(화면 필요), `service` → `sendBroadcast()`(UI 없는 백그라운드 처리).
- 규칙:
  - `name`: **대문자와 밑줄만** (예 `FIND_CHEAPEST_STATION`), 스키마 안에서 유일, 2~3 단어 권장.
  - `description`: **영어**, 한 문장(최대 두 문장), 구체적 예시 금지, Gleo의 동작을 조작하거나 다른 앱 사용을 막는 문구 금지.
  - 파라미터 `type`은 하나만(`string`/`number`/`boolean`/`array`), `enum`은 문자열만, `null` 불가.
  - `required`에는 필수 파라미터만. 각 function은 다른 호출 없이 단독으로 명령을 수행하도록 설계.
- 문서 예시에는 `invokeWord` 필드가 있으나 필드 설명에는 없습니다 (❓ 필수 여부·동작 불명).

### 2.4 Gleo Action: Gleo → 우리 앱

- Gleo가 Intent로 변환: Intent **action = function name**, `setPackage(packageName)`, 파라미터는 JSON 문자열로 **`ACTION_PARAM` extra** 에 담김.
- `activity`: Activity가 `intent.extras`의 `ACTION_PARAM`을 읽음. `service`: `BroadcastReceiver`가 `intent.action`을 확인하고 `getStringExtra("ACTION_PARAM")`을 JSON으로 파싱.
- 매니페스트에 function 이름과 같은 action의 `intent-filter`가 필요합니다 (예 `<action android:name="ADD_REMINDER" />`).

### 2.5 Callback: 우리 앱 → Gleo

- **브로드캐스트 action `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`**, extras: `packageName`(우리 패키지), `response`(JSON 문자열). `setPackage`는 쓰지 않고 action 문자열로 Gleo를 식별.
- `response` JSON 필드:

| 필드 | 필수 | 내용 |
| --- | :-: | --- |
| `functionName` | ✅ | 호출된 function 이름과 동일 |
| `status` | ✅ | `success` / `asking`(추가 정보·사용자 확인 필요) / `fail`(비즈니스 실패·오류·타임아웃) |
| `message` | ✅ | 결과를 설명하는 **영어** 짧은 문장 (검색어·이름 등은 다른 언어 가능) |
| `mode` | | `voice`(기본) / `beep` / `silent`(앱이 직접 소리를 낼 때) |
| `data` | | JSON 배열을 **문자열로** 직렬화. Gleo가 필요한 정보만, 넣기를 강력 권장 |

- **7초 안에 응답하지 않으면 Gleo가 작업을 취소**합니다.

### 2.6 전달·승인

- 📖 스키마를 전달받으면 Gleo 측이 가이드라인 준수 여부를 **검토**하고, "승인 후에는 통합이 자동으로 활성화"됩니다.
- 📖 **제출 경로(이메일·콘솔)는 문서에 없습니다.** "추가 안내가 필요하면 이 문서의 저자에게 문의"라고만 적혀 있습니다. 문서 하단의 일반 문의처는 partnership@pleos.ai 이지만 스키마 전용 창구로 명시된 것은 아닙니다.
- 📖 스키마를 읽는 시점: ① **Gleo AI 앱이 부팅될 때 한 번**, ② ADB 설치 또는 앱 마켓 설치·삭제 시.
- 📖 테스트 방법(에뮬레이터·실차)은 이 가이드들에 없습니다. App Test Guide는 폼 팩터 화면 테스트만 다룹니다.

## 3. 에뮬레이터 확인 🔬 (2026-11-08)

| 확인 | 결과 |
| --- | --- |
| Gleo AI 앱 본체 | **없음.** 패키지 이름에 `gleo`가 들어간 것은 `ai.pleos.gleoai.model.provider` 하나뿐 |
| `ai.pleos.gleoai.model.provider` 정체 | 시스템 앱(`/system_ext/app/LLMModelProviderSerivce`)이며 부팅·`SPEECH_SERVICE_STARTED` 같은 이벤트를 받는 리시버만 확인됨. 음성 비서 UI나 function 호출 기능은 확인 못 함 |
| `APP_ACTION_RESPONSE`를 받는 수신자 | **없음** (`cmd package query-receivers` → "No receivers found") |
| 결론 | Gleo가 우리 앱을 호출하는 쪽도, 우리 Callback을 받는 쪽도 이 에뮬레이터에는 없음. **Gleo 연동 전 구간은 현재 에뮬레이터에서 시험 불가** |

## 4. 우리가 할 수 있는 세팅 (단계)

| 단계 | 내용 | 담당 |
| :-: | --- | --- |
| 1 | Pleos Playground 프로젝트 + **앱 마켓 등록**(스키마의 `version`이 "앱 마켓 등록 버전"이므로 등록이 선행될 가능성) | 사용자 |
| 2 | **Pleos 측에 문의**: 스키마 제출 경로, 에뮬레이터·실차 테스트 방법, 공모전 기간 중 승인 가능 여부 (6장 질문 목록 사용) | 사용자 |
| 3 | `schema.json` 작성 (5장 초안) | 제가 |
| 4 | 앱에 Gleo Action 수신부(`BroadcastReceiver`)와 Callback 송신 함수 구현 | 제가 (제품 언어 D-13 결정 후) |
| 5 | **Gleo 없이도 가능한 시험:** `adb shell am broadcast`로 Gleo Action을 흉내 내 우리 수신부·응답 JSON을 검증 | 제가 (요청 시) |
| 6 | 스키마를 `assets/schema.json`에 포함해 제출, 승인 후 실제 Gleo로 시험 | 사용자 + Pleos |

5단계는 Gleo가 실제로 호출하는 것은 아니므로 "우리 쪽 구현이 규격대로인지"만 확인합니다. Gleo가 스키마를 어떻게 해석해 function을 고르는지는 확인할 수 없습니다.

## 5. 짠드라이브 적용 초안

> **초안이며 Pleos 검증을 받지 않았습니다.** `packageName`은 앱 ID가 정해지기 전(D-13)이라 자리표시자입니다. 규칙(대문자 이름, 영어 description, 최대 3개, 단일 type)은 2.3절을 따랐습니다.

```json
{
  "packageName": "com.zzandrive.app",
  "appDescription": "A driving cost assistant that finds the cheapest fuel station, charger or route and tracks the money the user has actually saved.",
  "version": "0.1.0",
  "actionCalls": [
    {
      "type": "function",
      "function": {
        "name": "FIND_CHEAPEST_STATION",
        "description": "Find the fuel station or EV charger with the lowest total cost, including the detour, near the current route.",
        "parameters": {
          "type": "object",
          "properties": {
            "stationType": {
              "type": "string",
              "description": "Whether to look for a fuel station or an EV charger.",
              "enum": ["FUEL", "CHARGE"]
            }
          },
          "required": ["stationType"]
        },
        "component": "service"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "COMPARE_ROUTE_COST",
        "description": "Compare the total cost of tolls and energy for the available routes to a destination and recommend the cheapest one.",
        "parameters": {
          "type": "object",
          "properties": {
            "destination": {
              "type": "string",
              "description": "Name or address of the destination."
            }
          },
          "required": ["destination"]
        },
        "component": "service"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "GET_SAVINGS_SUMMARY",
        "description": "Report how much money the user has saved and the current character level.",
        "parameters": {
          "type": "object",
          "properties": {
            "period": {
              "type": "string",
              "description": "The period to summarize.",
              "enum": ["TODAY", "MONTH"]
            }
          },
          "required": ["period"]
        },
        "component": "activity"
      }
    }
  ]
}
```

기능 매핑과 설계 판단:

| 기획서 | Gleo Interface로 가능한 형태 | 비고 |
| --- | --- | --- |
| F02·F03 추천 ("가장 싼 곳 찾아줘") | `FIND_CHEAPEST_STATION` | 금액은 우리 `Recommender`가 계산하고 Gleo는 결과를 전달만 (spec.md 5장 F05 원칙과 일치) |
| F04 경로 비교 | `COMPARE_ROUTE_COST` | 후보 계산은 외부 경로 API ([api-feasibility.md](api-feasibility.md) 참고) |
| F06·F08 절약 현황 | `GET_SAVINGS_SUMMARY` | 레벨·월간 손익 표시. 주행 중 상세 화면은 R-33 때문에 제한 필요 |
| **R-20 "~갈까요?" → "응" 승인** | Callback `status: "asking"` 으로 Gleo가 사용자에게 확인을 받는 방식이 **가능할 수 있음** | ❓ 문서에는 `asking`이 "추가 정보·사용자 확인 필요"로만 정의됨. 사용자의 "응"이 같은 function으로 다시 돌아오는지는 미확인 |
| 캐릭터 말투·음식 비유 | `message`는 **영어**여야 하고 `mode`가 `voice`면 Gleo가 문장을 만듦 | ❓ 한국어 캐릭터 말투를 Gleo 음성으로 유지하기는 어려움. 앱이 직접 말하려면 `mode: "silent"` + 자체 TTS(현재 "준비 중") |
| 앱이 먼저 말 걸기 (연료 부족 등) | ❌ Gleo Interface는 Gleo → 앱 방향만 문서화됨 | 앱 자체 TTS 또는 알림 필요 |

## 6. 위험과 한계

1. **승인 의존:** 승인 없이는 실제 Gleo 연동이 활성화되지 않고, 일정 보장이 없습니다. 공모전 접수(11/15)·PT(12/3~12/4)에는 **Gleo 없이 동작하는 구성**을 기본으로 하고, 연동은 가산 기능으로 두는 것이 안전합니다.
2. **에뮬레이터 시험 불가:** Gleo 앱이 없어 실제 호출 흐름을 시연할 수 없습니다. 영상 시연에 Gleo 음성을 넣으려면 Pleos 측 환경이 필요합니다.
3. **7초 제한:** 외부 API(유가·경로) 조회가 7초를 넘길 수 있으므로 캐시된 결과로 먼저 응답해야 합니다.
4. **function 3개 제한:** 짠드라이브 기능 중 음성으로 시킬 3개를 골라야 합니다 (위 초안은 한 가지 안).
5. **언어:** `description`과 `message`는 영어. 한국어 발화 이해는 Gleo가 담당하지만, 우리가 `message`로 한국어 캐릭터 문장을 내보낼 수 있는지는 ❓입니다.

## 7. Pleos에 문의할 질문 (사용자용)

1. App Schema는 어디로, 어떤 형식으로 제출하나요? (이메일/콘솔/App Market Console)
2. 에뮬레이터(또는 개발용 단말)에서 Gleo AI와의 연동을 시험하는 방법이 있나요? Gleo AI 앱을 에뮬레이터에 설치할 수 있나요?
3. 공모전 일정(2026-11-15 접수, 12-03 PT) 안에 스키마 검토·승인이 가능한가요? 승인 소요 기간은요?
4. 스키마의 `version`은 앱 마켓에 앱을 등록한 뒤에만 쓸 수 있나요? 공모전 참가 앱도 앱 마켓 등록이 필요한가요?
5. Callback `status: "asking"`을 보낸 뒤 사용자의 "응/아니오" 답은 어떤 경로로 앱에 전달되나요?
6. `message`는 영어만 가능한가요? 한국어 문장을 보내도 되나요?
7. 문서 예시의 `invokeWord` 필드는 무엇이고 필수인가요?
8. 앱이 먼저 Gleo에게 말을 시키거나 질문을 보낼 수 있는 API가 있나요? (LLM SDK 종료 후 대체 수단)
