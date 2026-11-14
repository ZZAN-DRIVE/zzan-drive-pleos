# app

Pleos Connect(Android Automotive)에서 실행되는 앱 모듈이다. 화면, 음성 안내와 음성 답(TTS·STT), 캐릭터 애니메이션, 모듈 연결을 맡는다.

- 계산·정산·감지·레벨 로직은 `:core`에 있다(JVM 단위 테스트). 차량 데이터는 `:data:source-pleos`(실차)와 `:data:source-mock`(시연)을 거친다.
- 공모전 요건은 Java 또는 Kotlin, Android API 28 이상, Gradle 8.0 이상이다. 앱은 Kotlin으로 만들고 패키지는 `com.zzandrive.app`이다(spec.md D-13).

## 구성

| 파일 | 내용 |
| --- | --- |
| `ZzanApp.kt` | Application. 실차 런타임(`LiveGraph`: Pleos Vehicle·NaviHelper·FusedLocation, 기기 파일 원장)과 시연 런타임(`DemoGraph`: 시연 시계열, 메모리 원장)을 만든다. 주행 중 화면이 막혀도 감지·정산·음성이 계속되도록 Activity가 아니라 Application 단위로 둔다. 시연 중에는 실차 런타임이 말하지 않는다 |
| `runtime/ZzanController.kt` | 앱 흐름 전체. [충전소 찾기](기준 위치 → 넣을 양 → 도로 5 km 안 후보 비교)와 잔량 알림(설정한 기준 %) → 비교 결과와 동의 질문(음성, 5초 듣기) → 승인 시 내비 전달 → 충전·주유 감지 → 장소 매칭(300 m) → 정산·원장 → 레벨 → 정차 후 레벨업. 승인한 곳을 넣지 않고 지나치면 기회비용. 질문 순서는 `:core`의 `dialog/StationDialog.kt`(순수 함수), 답 해석은 `dialog/AnswerParser.kt`. 화면은 이 클래스가 내는 `UiState`만 그린다 |
| `runtime/Platform.kt` | 스케줄러, `Speaker`·`Listener`·`Navigator`·`LedgerStore` 인터페이스, 원장 파일(JSON Lines, 앱 전용 저장소, 기기 밖으로 보내지 않음), 설정(알림 기준 %, 음성 답, 시급, 마지막으로 고른 양)과 고른 캐릭터. "처음부터"는 원장, 고른 캐릭터, 레벨업 연출 기록을 지우고 설정은 남긴다 |
| `voice/PleosTtsSpeaker.kt` | Pleos TTS(`ON_DEVICE`). 문장을 들어온 순서대로 말하되, 이미 끝난 질문(탭으로 답함, 다른 질문으로 바뀜, 시연 종료)의 문장은 버리거나 멈춘다(`speakWhile`·`refresh`). 오디오 포커스(`USAGE_MEDIA`·`CONTENT_TYPE_SPEECH`·`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)를 받은 뒤에만 말한다. 받지 못하면 그 문장을 건너뛰고, 말하는 중에 잃으면 멈춘다. 캐릭터별 화자(`setSpeakerType`): 짠돌이 `sanghyeok`, 짠순이 `haeun` |
| `voice/PleosSttListener.kt` | Pleos SpeechToText(`ON_DEVICE`). 앱이 물은 뒤 5초 듣는다(SDK에 시간 제한이 없어 앱 타이머로 `stop`). 듣는 동안 화면에 "듣는 중…". `recognize`(debug 명령 `hearpcm`)는 녹음한 16 kHz PCM을 `sendAudio`로 넣는다 |
| `voice/SpeechClip.kt` | debug 명령 `hearpcm`용. 앱 전용 저장소의 `files/stt/NAME.wav`(16 kHz·모노·16비트, 없으면 `NAME.pcm`)를 읽고 앞뒤에 무음을 붙인다 |
| `ui/CharacterStageView.kt` | 캐릭터 연출. 같은 자리에서 클립을 바꿔 재생한다: 레벨별 반복 클립(들고 대기, 없으면 대기)과 한 번 재생하는 클립(레벨업 → 새 음식 먹기, 탭하면 먹기, 정산 뒤 기쁨·시무룩). 보이는 크기로 디코드해 1:1로 그리고(최대 720 px), 다음 클립은 250 ms 페이드로 잇는다. 화면이 안 보이거나(onStop) 시연 데이터가 주행 중이면 멈춘다. 클립 순서 규칙은 `:core`의 `motion/CharacterClips.kt`(단위 테스트) |
| `ui/Palette.kt` | Pleos Connect 디자인 토큰의 주간·야간 색(배경·카드·팝업·글자 4단계·구분선·보조 버튼·선택 칩)과 앱만의 두 색(주 버튼의 짠돌이 청록, 레벨 표시의 번개 노랑), 손실 금액의 빨강. 모든 글자 대비 4.5:1 이상(계산으로 확인) |
| `ui/Pleos.kt` | Pleos 글자 단계(60·48·40·36·32·30·28·26·24, 굵기 400·600·700), 버튼 크기(높이 100·80·64, 모서리 32·24·16), 간격·모서리·팝업 폭, 한글 낱말이 줄 중간에서 끊기지 않게 하는 `keepAll` |
| `ui/CharacterArtView.kt` | 클립이 하나도 없을 때만 쓰는 안전망 그림 |
| `MainActivity.kt` | 제품 화면(Pleos 디자인 가이드 배치). 위쪽 페이지 내비게이션 바: 앱 표시·이름, 데이터 출처("실차 데이터"/"시연 데이터"), [시연], [설정]. 왼쪽 단(운전석 쪽): 누적 절약액(크게)과 이번 달·오늘 손익, 캐릭터, 레벨·다음 음식까지 남은 금액, 주 버튼 [충전소 찾기]/[주유소 찾기]. 오른쪽 단: 캐릭터의 말, 결과 줄, 카드 하나(질문 2열 버튼 / 비교 목록: 첫 줄 결론, 장소·거리·단가·합계 또는 총 지출·최저 대비, 응/아니/왜 거기야? / 출발 전 경로 / 레벨업 / 이번 주행 차감 / 안내), 차량 상태 4칸. 팝업(Pleos 시스템 팝업 규격): 설정(알림 기준 %, 음성 답, 시급, 캐릭터, 오픈소스 고지), 시연(시연 B·A·C, 실차 모드, 처음부터: 확인 후 삭제), 캐릭터 선택, 위치 권한 안내. 창 폭 1200dp 이상은 두 단, 그 아래(세로 창)는 금액·레벨과 작은 캐릭터 아래에 카드. Back은 팝업을 닫고, 없으면 앱을 뒤로 보낸다 |
| `diag/DiagnosticsActivity.kt` | SDK 연결 진단 화면("짠드라이브 · Pleos 연결 진단", debug 전용). Vehicle·NaviHelper·TTS·STT·FusedLocation·주행 제한 결과를 logcat 태그 `JjanShell`로 남긴다. `scripts/verify-app.sh`가 이 화면을 실행한다 |

- 제품 화면은 정차 전용이다. `distractionOptimized`를 선언하지 않았으므로 주행 중에는 시스템이 화면을 막는다(R-33). 주행 중에도 음성은 나오며, 레벨업 화면과 레벨업 음성은 정차한 뒤에 나온다(R-46).
- 진단 화면과 디버그 명령은 **debug 빌드에서만** adb로 실행할 수 있다(`manifestPlaceholders["diagExported"]`).
- `abiFilters`는 `arm64-v8a`(Apple Silicon 에뮬레이터·차량)와 `x86_64`(Windows 에뮬레이터)다. TTS·STT가 쓰는 `libuacore.so`가 이 두 ABI를 제공한다.
- 캐릭터는 3D로 미리 렌더링한 투명 애니메이션 WebP(720×720, 24fps)다. 프레임워크 `AnimatedImageDrawable`로 재생한다. 앱 안에서 3D를 그리지 않으므로 3D 엔진(Filament 등) 같은 추가 라이브러리가 없다.
- 캐릭터 에셋: `src/main/assets/character/<zzandori|zzansuni>/`. 이름 규칙 `*_idle|levelup|happy|sad.webp`, `*_lv<N>_<음식>_(eat|hold).webp`를 따르면 코드 수정 없이 인식한다. 짠순이 폴더가 비어 있으면 짠돌이 그림을 쓰고 "그림 준비 중"을 표시한다. WebP는 압축하지 않고 APK에 넣는다(`noCompress`).
- 화면은 onStart~onStop 동안만 상태를 구독하고 그린다. 창 크기·방향 변경은 `configChanges`로 받아 배치만 바꾼다(캐릭터 재생이 끊기지 않음). 주간·야간 전환은 화면을 다시 만든다.

## 실행

```bash
./scripts/bootstrap.sh                 # 설치부터 검증까지: 단위 테스트, SDK 진단, 제품 화면의 실제 차량 데이터 수신
./scripts/e2e-scenario-b.sh            # 시나리오 B를 실제 차량 데이터로 끝까지(약 2분). 바꾼 차량 값은 끝나면 되돌리고, 실차 원장은 시나리오 시작점으로 바뀐다
adb -s emulator-5554 logcat -s ZzanApp ZzanDemo   # 제품 런타임 로그(실차 ZzanApp, 시연 ZzanDemo)
adb -s emulator-5554 logcat -s ZzanTts ZzanStt ZzanPleos ZzanNavi  # 음성 합성·인식, Pleos 차량 값, 내비 전달
adb -s emulator-5554 logcat -s JjanShell          # 진단 화면
adb -s emulator-5554 shell am start -n com.zzandrive.app/.diag.DiagnosticsActivity --ez zzan.selftest true
```

`zzan.selftest`(debug 빌드 전용)는 TTS로 한 번 말하고 STT로 5초 듣는다.

### 런타임 로그

| 줄 | 뜻 |
| --- | --- |
| `READY mode=EV demo=false origin=PLEOS_SDK state=…` | 런타임 시작. 원장에서 복원한 레벨·적립 |
| `DATA first energy=… origin=PLEOS_SDK` | 시작 후 처음 받은 차량 값 |
| `LOW level=25% threshold=25 parked=false` | 잔량이 알림 기준에 닿음. `suppressed (open)`은 진행 중인 추천이 있어 묻지 않음 |
| `NOTICE low while driving` | 음성 답이 꺼진 주행 중: 질문 없이 한 줄만, 정차 후 묻기 |
| `ASK reference` / `ASK fill reason=find\|low` | 기준 위치·넣을 양 질문 |
| `LISTEN expect=FILL window=5000` | 질문 뒤 5초 듣기 시작 |
| `HEARD text=응 expect=CONSENT intent=Yes` | 알아들은 말과 해석. `stale`은 이미 지난 질문의 답이라 버림. "응"은 답 전체가 동의 표현일 때만(질문·"그냥 갈게"는 아님) |
| `ASK unheard expect=FILL` | 질문이 재생되지 못함(오디오 포커스를 받지 못함 등). 주행 중이면 정차 후 다시 물음 |
| `ASK consent at the stop` | 주행 중 추천 질문을 못 들었거나 답이 없었음: 정차하면 "아까 못 들었지? … 들를래?"로 한 번 더 |
| `FIND ignored (route card open)` | 출발 전 경로 비교 카드가 열려 있거나 곧 열려서 찾기를 받지 않음([확인] 먼저) |
| `ROUTES skipped (busy)` | 질문이나 결정이 열려 있어 출발 전 경로 비교를 열지 않음 |
| `ANSWER reference=… / ANSWER fill=…` | 화면 칩이나 음성으로 받은 답 |
| `CAND ref=current in=ev-a,ev-b excluded=ev-far:6.2` | 기준 위치에서 도로 5 km 안 후보와 뺀 후보(km) |
| `ROUTE dest=강릉 remainKm=100 branch=UNREACHABLE limitKm=82.6 reachable=… reach=no` | 목적지 분기: 주행 가능 거리(spec D-33), 그 안의 주유소, 목적지까지 갈 수 있는지 |
| `TOTAL ref=1855.43 wage=5000 qty=30 route-c:15134(0) route-d:15450(-316) …` | 주유소를 들르는 경로의 총 지출과 최저 대비 손익(D-32, D-34) |
| `REC none (no stop needed) …` · `ADVICE closed` | 목적지까지 갈 수 있고 들르지 않는 편이 덜 들어 안내만 함(기록 없음) |
| `REC … baseline=ev-a best=ev-b expected=2323 mode=COST_INCLUDED rule=VS_NEAREST qty=…` | 추천, 예상 절약액, 비교 방식(D-24~D-27, `ROUTE_TOTAL`은 D-32), 적립 기준(D-29), 예정량 |
| `DECIDE approved=true … navigation=true` | 승인과 내비 전달. `DECIDE nearest`는 "가까운 데로 가" |
| `PASS station=ev-b rec=… amount=77` · `OPP amount=77 applied=77 …` | 승인한 곳을 넣지 않고 지나침(300 m 안 → 500 m 밖), 기회비용 차감(D-28) |
| `ROUTES dest=강릉 best=national rival=highway faster=-70 costMore=-7575 wage=5000 highway:37892+13333 …` | 출발 전 경로 비교(D-31): 경로별 운행비 + 시간 환산. `:stop`이 붙은 경로는 도착 전에 알림 기준에 닿아 추천에서 뺌. `ROUTES closed`는 [확인] |
| `DETECT … q=40.000 basis=ENERGY … station=ev-b` | 충전·주유 감지와 장소 매칭 |
| `SETTLE amount=2323 level=3 accrual=1323 remaining=5677` | 정산 확정. `SETTLE skipped reason=…`은 적립하지 않은 이유 |
| `TRIP start …` · `HABIT accel … n=1 cost=136.00` · `HABIT idle over 180s` · `HABIT speeding start …` · `HABIT decel … recorded` | 운전 습관 감지(D-35~D-37). 주행 중에는 금액 없는 한 줄만 말함 |
| `TRIP end … raw=376.90` · `PENALTY trip=… full=377 applied=300 level=1 accrual=0` | 주행 끝 정산(D-38): 내역, 차감액, 레벨 적립액에서 실제로 뺀 금액 |
| `SAY …` | 음성 안내 문장 |
| `MUTED (demo running) …` | 시연 중이라 실차 런타임이 말하지 않은 문장 |
| `SHOW level-up 3` | 레벨업 연출 시작(정차 중) |
| `CLIP jjandol_lv3_jjajang_eat.webp 690px` | 캐릭터 클립 재생 시작과 디코드 크기. `CLIP stop`은 화면이 안 보여 멈춤 |

### 디버그 명령 (debug 빌드 전용)

```bash
APP=com.zzandrive.app/.MainActivity
adb -s emulator-5554 shell am start -n $APP --es zzan.demo B      # 시연 B(충전). A = 주유, off = 실차로 돌아가기
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd seedB   # 실차 원장을 시나리오 B 시작점(레벨 2·3,000원·짠순이)으로. seedA = 시나리오 A 시작점(레벨 0·1,000원·짠돌이)
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd approve # 응. decline = 아니, explain = 왜 거기야?, compare = 지금 비교, reset = 처음부터
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd level --ei zzan.level 7 --es zzan.persona zzansuni  # 레벨 7 레벨업 화면·음성 (정차 중)
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd find                       # [충전소 찾기]. cancel = 질문 닫기
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd ref --es zzan.arg current  # 기준 위치: current, parents(본가)
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd fill --es zzan.arg q40     # 넣을 양: full, q40, q50, m10000, m20000, m30000, m50000, skip
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd threshold --es zzan.arg 25 # 알림 기준 %(10~40)
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd voice --es zzan.arg off    # 음성 답 끄기(on = 켜기)
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd hear --es zzan.arg "응"   # 음성 인식 결과를 글자로 넣기
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd hearpcm --es zzan.arg yes  # files/stt/yes.wav를 SpeechToText로 인식
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd routes                     # 출발 전 경로 비교(경로 데이터가 있는 시연 A에서). routesok = [확인]
adb -s emulator-5554 shell am start -n $APP --es zzan.cmd wage --es zzan.arg 10000   # 시급: 5000, 10000, 20000
```

- `hearpcm`의 소리 파일은 `scripts/lib/scenario.sh`의 `stt_clip`이 Mac의 한국어 음성(`say`, `afconvert`)으로 만들어 `run-as`로 앱 전용 저장소에 넣는다. 시스템 음성은 재배포할 수 없으므로 저장소에 넣지 않는다.
