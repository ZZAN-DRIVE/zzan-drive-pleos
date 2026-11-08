# app

Pleos Connect(Android Automotive)에서 실행되는 앱 모듈. 화면(주행/정차), 음성 UI(TTS·STT), 캐릭터 연출, 각 모듈의 의존성 연결을 담당한다.

- 계산·정산·레벨 로직은 여기에 두지 않는다 (`core/`에서 가져다 쓴다).
- 구현 언어는 아직 확정되지 않았다 (spec.md D-13). 공모전 요건: Java 또는 Kotlin, Android API 28 이상, Gradle 8.0 이상.
