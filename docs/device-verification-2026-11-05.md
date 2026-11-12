# 2026-11-05 실기기 검증 / 0.2.0

기기: iPlay60 mini Turbo, Android 14(API 34), 1200×1920px, 320dpi. 세로 IME 폭 약 600dp, 가로 약 960dp. USB ADB를 통해 개발용 APK를 설치하고 실제 IME 창에 터치 이벤트를 주입했습니다. 앱의 로컬 EditText·WebView 시험 화면만 입력 대상으로 사용했습니다.

## 구현 및 수정

- 분할 자판의 가운데 간격을 24~160dp로 조절하고 DataStore에 저장합니다. 창이 좁으면 최대 간격을 폭의 1/4로 제한합니다.
- 분할형에서 양쪽에 공백 키를 제공해 어느 손으로도 공백을 입력할 수 있게 했습니다.
- 이메일·URL 배치에서 마침표가 `@`·`/`로 대체되어 바로 입력할 수 없던 문제를 수정했습니다. 이제 `@`와 `.`, `/`와 `.`가 각각 함께 표시됩니다.
- 키의 의미에 따른 안정적인 resource ID를 추가했습니다. 자판 라벨·Shift·언어가 바뀌어도 같은 키를 찾아 시험할 수 있습니다.
- 입력 갱신 때 키 목록을 매 버튼마다 반복해서 펼치지 않고 한 번 계산합니다.
- 릴리스에 포함되지 않는 로컬 기기 시험 Activity와 UI Automator 테스트, 재현용 PowerShell 스크립트를 추가했습니다.

## 결과

| 검사 | 결과 |
|---|---|
| 순수 Kotlin 한글 테스트 | 8개 통과, 11,172개 Unicode 음절 대조 포함 |
| Robolectric 편집·수명주기·View 테스트 | 18개 통과 |
| 실기기 시나리오 | 서로 다른 15개 메서드 통과, 아래 실행 묶음 참조 |
| 연속 입력 | 40키 / 2.597초, 약 15.4키/초, 기대 문자열 일치 |
| 화면 전환 | 가로↔세로 총 100회, 매 전환의 기대 문자열 일치 |
| 디버그·릴리스 빌드 | 성공, release는 unsigned 산출물 |
| 디버그·릴리스 Lint | 각각 오류 0개, 의존성 업데이트 알림 4개 |
| 디버그 APK 서명 | v2 서명 검증 성공 |
| 요청 권한 | 없음, INTERNET 권한 없음 |
| 릴리스 시험 코드 제외 | manifest에 ImeTestActivity 없음 확인 |

실기기 15개 시나리오는 한글 조합/역삭제, 선택/커서 이동, 한영/Shift/Caps Lock, 확정 grapheme 삭제, 숫자/전화/검색/줄바꿈, 비밀번호 미리보기, 입력란 전환/restartInput, 회전/분할 배치, 반복 삭제/터치 취소, 연속 입력, WebView 3종, 두 포인터 Shift+문자, 자동 대문자, 100회 회전, 문맥 배치/전체 Enter 동작입니다.

일반 입력에서 키를 누른 동안 미리보기가 실제 표시되는 것을 대조군으로 확인한 뒤, 텍스트·보이는·웹·숫자 비밀번호에서 미리보기가 비어 있는지 실제 IME View를 검사했습니다. 숫자·전화 외에 이메일(`a@b.com`), URL(`a/b`), 날짜/시간 기호를 입력했습니다. Enter는 검색·완료·이동·다음·이전·보내기·줄바꿈과 사용자 지정 label/actionId를 확인했습니다.

## 재현 명령 및 증거

```powershell
.\scripts\build.ps1
.\scripts\device-tests.ps1 -DeviceSerial <adb-serial>
```

기기 스크립트는 APK를 제거하거나 앱 데이터를 지우지 않으며, 시험 중 변경한 앱 설정과 기존 입력기·회전 설정을 복원합니다. 화면을 깨우고 일반 잠금을 닫되, 인증이 필요한 잠금은 사용자가 해제해야 합니다. 실패 캡처는 앱이 표시되고 잠금이 해제된 경우에만 저장합니다.

| 실행 | 증거 |
|---|---|
| 기본 13개 시나리오 전체 통과 | [로그](device-runs/20261105-084038/instrumentation.txt) |
| 약15.4키/초 연속 입력 재측정 | [로그](device-runs/20261105-084346/instrumentation.txt), [측정값](device-runs/20261105-084346/screens/rapid-input.txt) |
| 100회 회전 추가 시험 | [로그](device-runs/20261105-084425/instrumentation.txt), [완료 횟수](device-runs/20261105-084425/screens/rotation-progress.txt) |
| 문맥 배치·전체 Enter, 마침표 수정 후 | [로그](device-runs/20261105-084940/instrumentation.txt) |
| 미리보기 대조군·비밀번호 4종 | [로그](device-runs/20261105-085027/instrumentation.txt) |

위 실행은 같은 0.2.0 개발 과정에서 나누어 수행했습니다. 처음 13개와 회전 스트레스 시험 이후 이메일/URL의 문장부호만 추가 수정했고, 해당 배치와 Enter를 재시험했습니다. 수정 후 단위 테스트 26개와 빌드·Lint를 다시 통과했습니다. 한 번의 전체 테스트 실행에서 15개를 모두 수행했다는 의미는 아닙니다.

초기 시험 준비 과정에서는 재설치 후 기본 IME 변경, 잠금 화면, 창 포커스, 시험 EditText 속성 변경 후 restartInput 누락, WebView의 native 포커스 누락이 있었습니다. 자동화 절차를 수정한 뒤 통과 기록을 확보했습니다. 이메일 마침표 누락은 제품 코드에서 수정하고 회귀 테스트를 추가했습니다. 준비 단계와 수정 전 실패 로그는 이전 실행 폴더에 남겨 두었습니다.

가로 분할형:

![가로 분할 자판과 양쪽 공백 키](device-runs/20261105-084038/screens/landscape-split.png)

WebView input·textarea·contenteditable:

![WebView 입력 시험](device-runs/20261105-084038/screens/webview-input.png)

## 산출물

개발용 APK: `app/build/outputs/apk/debug/app-debug.apk`, versionCode 2 / versionName 0.2.0. 연결된 태블릿에 설치했습니다.

SHA-256:

```text
01965555e4a0248565f71a21c72276a1718087e8b4cfafb6faa13354219e8cdf
```

## 남은 검증과 한계

- 100회 전환 시험의 Activity는 구성 변경을 직접 처리해 EditText 연결을 유지합니다. Activity 자체가 재생성되는 앱의 전환은 별도 시험이 필요합니다.
- iPlay 태블릿의 회전 시험을 Galaxy Fold 접기/펼치기나 힌지 회피 검증으로 간주하지 않습니다.
- TalkBack 실제 탐색·발화·입력, 외장 키보드, Samsung/Chrome/카카오톡/Compose 편집기는 아직 검증하지 않았습니다.
- 15.4키/초는 debug 빌드에서의 이벤트 주입 처리량입니다. release의 키 피드백 p95 50ms·내부 처리 p95 10ms 목표를 측정한 결과는 아닙니다.
- 다른 OS 버전과 모델, 사용자 화면 배율·글꼴, 실사용 엄지 도달성, 베타 및 배포 서명 작업은 남아 있습니다.
