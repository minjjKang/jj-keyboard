# 삼성 키보드 실기기 참고 및 0.3.0·0.3.1 검증

2026-11-05, Galaxy Tab S9+의 삼성 키보드를 직접 조작해 설정 구조·배열·입력 동작을 관찰하고 민찌 키보드 0.3.0에 반영했습니다. 개선한 IME의 실제 입력 검증은 별도 타겟 기기에서 수행했습니다.

| 역할 | 기기 | 환경 |
|---|---|---|
| 레퍼런스 | Galaxy Tab S9+ / SM-X810 / `R54WC00CQ4Y` | Android 16(API 36), 삼성 키보드 5.9.30.97, 1752×2800px, 설정된 density 300 |
| 타겟 | iPlay60 mini Turbo / `7ce508a5` | Android 14(API 34), 1200×1920px, 320dpi, 세로 약600dp·가로 약960dp |

## 관찰과 반영

| 삼성 기기에서 확인한 내용 | 민찌 키보드에 반영한 내용 |
|---|---|
| 스마트 입력 / 스타일 및 레이아웃 / 제스처 및 피드백으로 나뉜 설정 | 카드로 묶인 설정과 항목별 대화상자, 설정 요약 표시 |
| 세로·가로 각각 일반/분할/플로팅 모드 선택 | 세로·가로 각각 일반/분할 저장, 툴바에서 즉시 전환 |
| 회색 바탕, 밝은 문자 키, 구분되는 기능 키, 작은 모서리와 선형 아이콘 | 중립색 자판, 직접 그린 기능 아이콘, 어두운 테마와 고대비 옵션 |
| 넓은 태블릿 배열의 숫자 줄, Del, Backspace, Enter, 양쪽 Shift와 커서 키 | 폭840dp 이상에서 확장 배열; 설정으로 해제 가능, 일반·분할 모두 대응 |
| 키 위의 작은 보조 문자와 길게 누르기 | 보조 문자 표시 및 입력, 한글 Shift 자모와 영문 기호 지원 |
| 길게 누르기 0.2/0.3/0.5초 및 사용자 설정 | 설정에서 0.2/0.3/0.5초 선택 |
| 백스페이스 속도 느리게/보통/빠르게 | 세 단계 반복 삭제, 터치 직후 첫 삭제 및 해제 시 반복 중지 |
| 공백 키 좌우 빠른 스와이프로 한영 전환 | 빠른 스와이프 한영 전환, 공백을 추가하지 않음 |
| 공백 키 길게 누를 때 ‘커서 이동’ 상태 표시 | 길게 누른 뒤 좌우 이동으로 문자 경계 단위 커서 이동 |
| 글자 크기, 숫자 줄, 터치 피드백, 자동 대문자·공백 두 번 마침표 | 글자 크기80~140%, 숫자 줄·툴바·피드백 조절, 선택형 공백 두 번 마침표 |

폭840dp 기준과 반복 간격90/55/30ms는 이 앱에서 정한 값입니다. 삼성의 내부 임계값·반복 간격을 측정한 결과가 아닙니다. 플로팅 모드·AI·추천 단어·한자·Ctrl 단축키는 구현하지 않았습니다. 삼성의 이미지나 코드를 가져오지 않고 네이티브 View와 직접 그린 아이콘으로 구현했습니다.

### 레퍼런스 증거

- [처음 열린 설정 화면](reference/samsung-tab-s9plus-android16/01-settings.png)
- [세로·가로 모드](reference/samsung-tab-s9plus-android16/07-modes.png)
- [길게 누르기 시간](reference/samsung-tab-s9plus-android16/12-long-press.png), [삭제 속도](reference/samsung-tab-s9plus-android16/14-backspace-speed.png)
- [글자 크기와 실제 자판 미리보기](reference/samsung-tab-s9plus-android16/17-font-size.png)
- [실제 영어 태블릿 자판](reference/samsung-tab-s9plus-android16/21-live-keyboard-focused.png), [분할 자판](reference/samsung-tab-s9plus-android16/29-split-keyboard.png)
- [공백 키 스와이프 후 한국어](reference/samsung-tab-s9plus-android16/22-korean-swipe.png), [커서 이동 상태](reference/samsung-tab-s9plus-android16/26-space-cursor-held.png)

로컬 시험 입력란에서 실제 삼성 키를 눌러 `ㄱㅏㅂㅅ → 값`, 이어서 `ㅣ → 갑시`, 삭제 후 `갑ㅅ`을 확인했습니다. 민찌의 기존 입력 이력 기반 한글 엔진과 일치합니다. 최초 관찰에서 미완료였던 Del과 공백 키 커서 이동은 사용자가 잠금을 해제한 뒤 아래 추가 관찰에서 확인했습니다.

레퍼런스에는 입력창 제공용 개발 APK만 설치했고 삼성 키보드를 기본 입력기로 유지했습니다. 관찰 중 바꾼 모드는 일반/일반으로, 언어는 영어로 되돌렸습니다([설정 복귀 화면](reference/samsung-tab-s9plus-android16/32-reference-restored.png)). 이후 기기가 패턴 잠금 상태가 되어 추가 조작을 멈추고 사용자에게 직접 잠금 해제를 요청했습니다. 잠금 화면 캡처는 증거 폴더에서 제외했습니다.

종료 시 읽기 전용 확인에서 레퍼런스의 삼성 기본 입력기·자동 회전1·회전0, 타겟의 민찌 기본 입력기·자동 회전0·회전0을 확인했습니다. 타겟 설치 버전은0.3.0입니다. 두 기기는 이후 잠금 상태였고 최종 화면 열기에 포함된 잠금 해제 명령은 자동 승인 심사에서 거부되어 실행하지 않았습니다. 잠금 해제 없는 상태 확인으로 마무리했습니다. 캡처 도구에도 잠금 상태 차단을 추가했습니다.

## 편집 동작과 성능 보강

- Del과 좌우 커서 이동은 ICU 문자 경계를 사용해 이모지·결합 문자를 나누지 않도록 처리합니다. API31 이상 SurroundingText, API29의 ExtractedText 경로를 각각 지원합니다.
- 수동 Shift 선택이 편집기의 선택 위치 콜백으로 사라지는 문제를 수정했습니다.
- 공백 두 번 마침표는 기본 꺼짐입니다. 일반 영어 입력에서만 사용하며 비밀번호·이메일·URL·숫자 입력은 제외합니다.
- 문자마다 전체 키를 다시 꾸미던 동작을 줄였습니다. 배열·설정·Shift 등 표시 상태가 바뀔 때 갱신해 실제 연속 입력 처리량을 회복했습니다.
- 네트워크 권한과 입력 기록 기능은 추가하지 않았습니다. DataStore에는 설정만 저장합니다.

## 0.3.0 검사

| 검사 | 결과 |
|---|---|
| 한글 JVM 테스트 | 8개 통과, 전체11,172음절 대조 포함 |
| 앱 Robolectric 테스트 | 25개 통과, API29 커서 이동 대체 경로 포함 |
| 타겟 실기기 테스트 | 한 번의 전체 실행에서 **21개 통과**, 273.085초 |
| 연속 입력 | 40회 터치 / 2.583초 = 약15.49키/초, 기대 문자열 일치 |
| 세로↔가로 전환 | 100회, 매 전환 기대 문자열 일치 |
| debug·R8 release 빌드 | 성공, release 산출물은 unsigned |
| debug·release Lint | 각각 오류0, 의존성 새 버전 알림4 |
| debug APK 서명 | v2 서명 검증 성공 |
| 요청 권한 | 없음 |
| release manifest | 로컬 시험용 ImeTestActivity 제외 확인 |

21개에는 기존 조합·선택·입력 종류·비밀번호·WebView·다중 터치·재연결·회전 검사와 함께 새 Del/커서 키, 스페이스바 제스처, 보조 문자와 입력 지연, 모드/고대비/글자 크기/숫자 줄/숨김, 마침표 예외 조건, 실제 설정 대화상자 검사가 포함됩니다.

[전체 실행 로그](device-runs/20261105-093433/instrumentation.txt) · [기기 및 복원 정보](device-runs/20261105-093433/device.json) · [입력 측정값](device-runs/20261105-093433/screens/rapid-input.txt) · [회전 완료 기록](device-runs/20261105-093433/screens/rotation-progress.txt)

초기 전체 실행에서는 입력창 연결·회전 후 키 좌표·설정창 전환 대기 문제로 자동화가 실패했습니다([수정 전 로그](device-runs/20261105-092211/instrumentation.txt)). 시험 입력창에 고유 연결 표식을 부여하고 실제 연결을 기다리며, 현재 화면 폭과 배치 완료를 확인한 실제 키 좌표에 터치를 주입하도록 수정했습니다. 빠른 공백 두 번도 시간 조건에 맞게 주입합니다. 최종 제품 코드에서 위21개 전체 실행을 다시 통과했습니다. 시험은 앱 설정·기본 입력기·화면 회전 상태를 복원하며 데이터를 지우지 않습니다.

### 결과 화면

가로 확장 배열:

![가로 확장 배열](device-runs/20261105-093433/screens/extended-tablet-editing.png)

고대비 분할 배열:

![고대비 분할 배열](device-runs/20261105-093433/screens/high-contrast-split.png)

[설정 대화상자](device-runs/20261105-093433/screens/settings-layout-dialog.png) · [일반 가로 분할](device-runs/20261105-093433/screens/landscape-split.png)

## 잠금 해제 후 추가 관찰 및 0.3.1

사용자가 직접 잠금을 해제한 뒤 삼성 기본 입력기를 유지한 채 같은 로컬 입력창에서 다음을 확인했습니다.

| 조작 | 실제 결과 | 증거 |
|---|---|---|
| `123` 입력 → 왼쪽 커서 키1회 → Del | 커서 뒤의3을 지워 `12` | [직전](reference/samsung-tab-s9plus-android16/35-del-before.png), [직후](reference/samsung-tab-s9plus-android16/36-del-after.png) |
| `12345` 끝에서 공백 키 길게 누르기 → 왼쪽으로 단계 이동 →9 입력 | `123495`, 공백 없이 중간 위치에 삽입 | [직전](reference/samsung-tab-s9plus-android16/37-space-cursor-before.png), [직후](reference/samsung-tab-s9plus-android16/38-space-cursor-after.png) |
| 쉼표·마침표 각각 길게 누르기 | `!?`가 중간에 삽입되어 `12349!?5` | [결과](reference/samsung-tab-s9plus-android16/39-punctuation-long-press.png) |

Del과 커서 이동은 기존 구현 방향과 일치했습니다. 문장부호 길게 누르기를0.3.1에 추가했습니다. 작은 보조 문자·접근성 설명·접근성 long-click도 함께 제공하고 보조 문자 설정을 끄면 일반 쉼표·마침표를 입력합니다. 관찰 후 레퍼런스를 [삼성 키보드 설정 화면](reference/samsung-tab-s9plus-android16/40-reference-settings-returned.png)으로 되돌렸고 모드는 일반/일반입니다.

0.3.1 검사는 한글8개+앱26개로 **단위 테스트34개 통과**, debug·release 빌드 성공, Lint 각각 오류0·의존성 알림4입니다. 타겟에서 보조 문자 회귀 시나리오1개를 확대해 좁은 영어 배열과 가로 확장 한국어 배열의 문장부호 길게/짧게 입력, 보조 문자 끄기, 한글 쌍자음 및 기존 영문 길게 누르기 시간 조건을 통과했습니다([로그](device-runs/20261105-095257/instrumentation.txt), [화면](device-runs/20261105-095257/screens/punctuation-long-press.png)). 0.3.1에서21개 전체나100회 회전을 다시 실행한 것은 아닙니다.

추가 회귀 검사의 첫 두 실행은 새 키 입력 전에 IME 연결 준비 단계에서 실패했습니다([첫 로그](device-runs/20261105-095108/instrumentation.txt), [재현 로그](device-runs/20261105-095203/instrumentation.txt)). 시스템에서 현재 입력기의 `mCurMethod=null`·입력창 미연결을 확인했습니다. 현재 선택된 IME를 재설치한 경우 시험 스크립트가 이미 활성화된 다른 입력기를 거쳐 Minjj를 다시 선택하도록 수정한 뒤 통과했습니다. 기존 입력기·회전 설정은 finally에서 복원합니다. 테스트의 잠금 해제 명령도 제거해 사용자가 직접 해제한 기기에서만 진행합니다.

## 재현 및 현재 산출물

```powershell
.\scripts\build.ps1 -Tasks ':hangul:test',':app:testDebugUnitTest',':app:assembleDebug',':app:lintDebug',':app:assembleRelease',':app:lintRelease'
.\scripts\device-tests.ps1 -DeviceSerial 7ce508a5
```

APK: `app/build/outputs/apk/debug/app-debug.apk`, versionName0.3.1 / versionCode4. 타겟에 설치 완료했고 v2 서명과 요청 권한 없음을 재확인했습니다.

SHA-256:

```text
21fe77c62550f707c1cc793d61a067f0ea9a290e2d9a7b0251efe90563ad4165
```

삼성 Android16에서는 삼성 키보드를 관찰한 것이며 민찌 IME의 Android16 실기기 호환성을 검증한 것은 아닙니다. 100회 회전 시험 Activity는 구성 변경 시 연결을 유지합니다. Fold 힌지, Activity 재생성, TalkBack 실사용, 외장 키보드, 타사 메신저·Compose 편집기와 release 입력 지연 p95는 추가 검증 대상입니다. 15.49키/초는 debug 이벤트 주입 처리량입니다.
