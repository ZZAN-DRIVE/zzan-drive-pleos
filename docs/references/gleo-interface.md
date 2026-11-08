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
| 지금 에뮬레이터에서 시험할 수 있나 | ⚠️ 🔬 **Gleo AI 앱(`ai.umos.ambientai`)이 있고 우리 스키마를 읽으며, 우리 Callback도 받습니다.** 하지만 **Gleo의 음성 인식 엔진이 모델 데이터를 못 찾아 시작하지 못해서** 음성 호출은 현재 불가입니다 (3장) |
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

> **정정:** 이 문서의 초판은 "에뮬레이터에 Gleo AI 앱 본체가 없다"고 썼으나 **틀렸습니다.** 패키지 이름에 `gleo`가 들어간 것만 찾았고, 실제 Gleo AI 앱의 패키지는 `ai.umos.ambientai`였습니다. 아래는 정정된 내용입니다.

| 확인 | 결과 |
| --- | --- |
| Gleo AI 앱 본체 | **있음.** `ai.umos.ambientai` (Gleo AI). 에뮬레이터 앱 서랍에도 "Gleo AI" 아이콘이 있고, 로그에 `buildLaunchApps(Gleo AI) >> ai.umos.ambientai`가 찍힘 |
| 구성 요소 | 음성 상호작용 서비스(`AiAssistantService`, `BIND_VOICE_INTERACTION`), `AmbientAiReceiver`, `VehicleTtsReceiver`, `GleoNotificationListenerService` 등 |
| `ai.pleos.gleoai.model.provider` | 별개의 시스템 앱(모델 파일 제공용 서비스) |
| **스키마를 읽는가** | **읽음.** 우리 앱을 설치하자 `[GAA] [Schema] Loaded schema.json from com.zzandrive.probe:`와 함께 스키마 전문이 로그에 출력됨. `assets/schema.json`이 없을 때는 `com.zzandrive.probe has no schema.json` |
| 검증 오류 | 로그에 검증 오류·거부 메시지는 없었음 |
| 스키마가 **채택·활성화**됐는가 | ❓ 미확인. 로그의 스키마 개수(`getSchemaList - Boot End 4`)가 우리 스키마를 넣어도 4로 그대로여서, 목록에 들어갔는지 알 수 없음 |
| `APP_ACTION_RESPONSE` 수신자 | 매니페스트에는 없지만(`query-receivers` → "No receivers found") **Gleo가 코드에서 동적으로 받고 있음.** 우리가 보낸 응답에 `onReceive() : action:ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`, `processAppActionResponse`가 찍힘 → **Callback의 action 이름·extras(`packageName`, `response`) 규격이 맞음** |
| Gleo가 실제로 우리 function을 호출하는가 | ❌ **음성 명령이 인식되지 않아 호출까지 못 감.** 아래 "음성 엔진 실패" 참고 |
| **음성 엔진 실패 (원인 확인됨)** | Gleo의 음성 엔진(`Spokesman`)이 **초기화에 계속 실패**: `initialized fail!! check model data!! InvalidConfigError: Failed to open file: /data/user/10/ai.umos.ambientai/files/assets.zip/SpeechRecognitionConfig.dat` (한 번 기동 후 약 5,900회 반복). Gleo 상태는 계속 `Sleeping` |
| 모델 파일은 있는가 | 모델 제공 앱(`ai.pleos.gleoai.model.provider`, APK 1.4GB) 안에는 `...-connect-cas-...-assets.zip`(음성 서비스용)과 `...-connect-vr-spokesman-...-assets.zip`(Gleo 음성 인식용)이 **들어 있음.** 그러나 Gleo 저장 공간에는 전달되지 않은 것으로 보임. 음성 서비스(CaaS) 쪽 로그에는 `version downgrade detected. skip model update`(공유 요청은 `COMPLETED`)가 있음. **전달 실패의 정확한 원인은 ❓ 미확인** (앱 데이터 폴더는 권한이 없어 직접 확인 못 함) |
| 같은 원인의 다른 증상 | 우리 앱의 TTS `ON_DEVICE`가 `ON_DEVICE_ENGINE_ERROR`로 실패한 것과 같은 계열(음성 모델 데이터)로 **추정** (미확인). TTS `SERVER`는 로그에 `Client credentials missing for com.zzandrive.probe`로 실패 이유가 명시됨 |

**텍스트로 음성을 대신 넣을 수 있는가 (2026-11-08 조사): 찾지 못함.**

- Gleo AI 앱의 외부 진입점은 `ASSIST`(음성 비서 호출), `ambientai.intent.action.ENGINEERING_MODE`(엔지니어링 화면), `STOP_TTS`, 모델 공유 수신(`SHARE_SPEECH_FILE`) 정도이고, **텍스트 명령을 받는 진입점은 없었습니다.** 앱 코드 문자열에서도 텍스트 질의 입구는 보이지 않았습니다. (말뜻 이해를 하는 부분은 앱 밖의 SDK나 서버에 있는 것으로 보이지만 ❓ 미확인)
- 엔지니어링 화면(`adb shell am start --user 10 -a ambientai.intent.action.ENGINEERING_MODE`)에서 확인한 것:
  - 버전: Gleo AI App 4.2.0.15, GAAS 4.2.10, STT/TTS SDK 3.3.1, Navi SDK 2.2.4, Vehicle SDK 2.2.7, Speech SDK 1.17.1, Gleo Model Provider 4.1.21
  - **`Gleo VR DB` 항목이 비어 있음** → 음성 인식 DB가 없다는 오류 로그와 일치
  - 마이크 표시 `Unknown [4ch]`(빨간 아이콘), `'Gleo'라고 불러서 깨우기` 켜짐, WWD(깨우기 단어) 녹음 테스트 버튼
  - **서버 설정: REAL / HMG_GLOBAL / CORAL / STAGE / INT 중 CORAL 선택됨** → Gleo가 서버 환경에 연결하는 구조로 보임 (말뜻 이해·LLM이 서버에서 처리될 가능성. ❓ 미확인, 에뮬레이터에서 접속 가능한지와 인증은 모름)
- 따라서 텍스트를 끼워 넣더라도 서버 연결과 인증이 되지 않으면 function 선택까지 못 갈 수 있습니다.

**음성 없이 한 모의 시험 (2026-11-08):** `adb`로 Gleo Action을 직접 보냈습니다 (`am broadcast -a FIND_CHEAPEST_STATION -p com.zzandrive.probe --es ACTION_PARAM '{...}'`, `am start -a GET_SAVINGS_SUMMARY ...`).

| 확인 | 결과 |
| --- | --- |
| 우리 `BroadcastReceiver`(`component: service`) | ✅ 2개 function 모두 수신, `ACTION_PARAM` JSON 파싱 |
| 우리 Activity(`component: activity`) | ✅ 수신 |
| 우리가 보낸 Callback을 Gleo가 받는가 | ✅ 3건 모두 수신(`processAppActionResponse`) |
| Gleo가 Callback을 처리하는가 | ❌ 3건 모두 `Reject AppActionResponse`. 같은 줄에 `status: Sleeping`이 찍힘. 활성 대화(요청한 적 없는 응답)여서 거부한 것으로 **추정**하나 사유 문구는 로그에 없음 |

→ 우리 앱의 **구현은 규격대로**이고, 막힌 곳은 Gleo 쪽 음성 인식 준비입니다.

시험용으로 프로브 앱에 `assets/schema.json`(`packageName`은 `com.zzandrive.probe`), Gleo Action 수신부(`GleoActionReceiver`, `GET_SAVINGS_SUMMARY`용 Activity 필터), Callback 송신 함수(`GleoCallback`)를 넣어 두었습니다. Gleo가 실제로 호출하면 `adb logcat -s ZzanProbe:I`에 `GLEO  action received ...`와 `GLEO  callback sent ...`가 찍힙니다.

## 4. 우리가 할 수 있는 세팅 (단계)

| 단계 | 내용 | 담당 |
| :-: | --- | --- |
| 1 | Pleos Playground 프로젝트 + **앱 마켓 등록**(스키마의 `version`이 "앱 마켓 등록 버전"이므로 등록이 선행될 가능성) | 사용자 |
| 2 | **Pleos 측에 문의**: 스키마 제출 경로, 에뮬레이터·실차 테스트 방법, 공모전 기간 중 승인 가능 여부 (6장 질문 목록 사용) | 사용자 |
| 3 | `schema.json` 작성 (5장 초안) | 제가 |
| 4 | 앱에 Gleo Action 수신부(`BroadcastReceiver`)와 Callback 송신 함수 구현 | 제가 (제품 언어 D-13 결정 후) |
| 5 | **에뮬레이터 시험:** 프로브 앱에 스키마·수신부·Callback을 넣어 Gleo가 스키마를 읽는 것까지 확인함(3장). 남은 것은 Gleo AI에 **음성/입력 명령**을 줘서 실제 호출이 오는지 확인 | 사용자(음성·화면 조작) + 제가(로그 확인) |
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
2. **음성 호출 시험 불가(현재):** Gleo의 음성 엔진 모델 데이터 문제로 에뮬레이터에서 음성 명령이 인식되지 않습니다. 승인 여부가 호출에 영향을 주는지도 모릅니다.
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
9. Pleos Connect Emulator 2.1.0에서 Gleo AI 음성 인식이 `SpeechRecognitionConfig.dat`을 못 열어 초기화에 실패합니다(`/data/user/10/ai.umos.ambientai/files/assets.zip/...`). 모델 제공 앱에는 파일이 들어 있는데 Gleo에 전달되지 않습니다. 모델을 다시 전달하는 방법이나 알려진 이슈인지 알려 주세요.
10. "Coming Soon" 표기 SDK(FusedLocation, TextToSpeech, Vehicle Control)를 앱 심사·배포에서 사용할 수 있나요?
