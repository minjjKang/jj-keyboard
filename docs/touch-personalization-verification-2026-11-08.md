# 터치 개인화 메뉴·집중 학습·레이아웃 프로필 구현 및 검증

작성일: 2026-11-08. 구현 계획: [터치 개인화 구현 계획](touch-personalization-training-plan-2026-11-08.md).

이후 요청한 메뉴 그룹·관리 버튼 제거·상시 지도 표시의 최신 UI는 [화면 정리 기록](touch-personalization-ui-2026-11-08.md)을 참조한다.

## 구현 결과

`설정 → 터치 위치 개인화`에 `학습 데이터 확인`, `집중 학습`을 추가했다. 두 화면은 같은 Activity의 별도 진입 모드로 구현하고, 각각 한국어/영어 탭을 제공한다.

| 기능 | 구현한 동작 |
|---|---|
| 학습 데이터 확인 | 현재/저장 프로필 선택, 키별 일반·집중·유효 표본 수와 학습 단계, 평균 터치·보정 중심·공간 판정 지도 |
| 지도 | 실제 키와 기능 키의 측정 경계를 저장하고, 실제 `GeometrySnapshot`의 gap·인접 후보·resolver를 재사용. 문맥 보정은 제외한 공간 판정 미리보기 |
| 입력해 보기 | 실제 IME에서 입력하되 일반 학습도 중지. 이번 입력 점만 세션 메모리에 표시 |
| 집중 학습 | 부족한 키 중심 내장 단어 출제, 기본 10단어, 수정·다시 입력·건너뛰기·중간 종료, 단어 완료 버튼과 키보드 완료 키 지원 |
| 정답 대응 | 두벌식 복합 모음·겹받침·Shift 경로 재사용, 물리 키와 실제 출력 키를 별도 관측. 길이·대응·거리·출처가 검증된 표본만 저장 |
| 제외 조건 | 삭제·외부 편집·커서 이동·취소·제스처·좌표 없는 입력·누락·중복·모호한 대응·연결/배치 변경·초기화 전 대기 표본 제외 |
| 평가 | 학습 단어에서 제외한 고정 단어로 저장 없이 평가. 최소 30회 유효 문자 키 입력 후 기본/출력 오류율·보정 성공·잘못 바꾼 횟수 표시 |
| 프로필 | 측정 geometry로 작은 변경/유사 배치/새 배치 구분, 이전 배치 복귀, 선택 프로필 초기화·삭제, 전체 초기화 유지 |
| 저장 | v2 출처별 집계, 기존 v1 자료의 제한된 참고, 원자적 저장과 반영 확인, 저장 실패 표시, generation으로 초기화 경쟁 방지 |
| 수명주기 | 프로세스 내부 임의 토큰·패키지·필드·입력 연결로 전용 세션 제한. 화면/입력 연결 종료 시 입력과 원시 관측 정리 |

평소 자동 학습을 꺼도 명시적으로 시작한 집중 학습은 저장한다. 자동 학습 설정 자체와 문맥·추천·자동 대문자·마침표 설정은 덮어쓰지 않는다. 연습에서만 해당 동작을 제한하고 종료 후 원래 상태로 복귀한다.

## 적용한 모델 정책

- 언어·방향·실제 스플릿·확장 배열·숫자행을 분리한다. 설정 요청값 대신 배치 완료 후의 실제 키 경계·화면 위치·간격을 비교한다.
- 같은 프로필 비교는 최초 대표 배치를 기준으로 한다. 너비/높이 5%, 중심 이동 각 축 키 크기 10%, 키 간격 1dp 이내를 초기 허용값으로 둔다.
- 유사 참고는 크기 15%, 중심 이동 30%, 키 간격 2dp 이내의 같은 종류 배치 하나로 제한한다. 원본 통계를 복사하거나 표본 수에 합산하지 않는다.
- 일반 표본 가중치 0.35, 검증된 집중 표본 1.0, 참고 영향력 최대 25%, 자체 유효 표본 30 이상이면 참고 중지, 최대 보정 이동 각 축 15%를 적용한다.
- 저장 프로필 최대 24개, 키별 소형 버킷 최대 8개·버킷당 16표본·키별 최대 128표본, 유효기간 30일, 파일 상한 2MiB다. 미리보기 프로필은 별도 임시 한도로 관리하고 유효 표본 저장 전에는 디스크에 쓰지 않는다.
- 키별 세션 채택 상한은 32표본이다. 단어 입력 중에는 모델 스냅샷을 고정하되 초기화 generation이 바뀌면 이전 모델을 적용하지 않는다.
- v1에는 정확한 배치가 없으므로 현재 배치의 정확한 학습 결과로 표시하지 않는다. 기존 구분이 맞는 경우의 약한 참고로만 남기고 30일 수명을 연장하지 않는다.

위 수치는 초기 제품 정책이며 실사용 정확도 개선의 최적값을 입증한 값은 아니다.

## 자동 검증

사용 환경은 저장소의 JDK 17·Gradle Wrapper 8.13·SDK 36이다.

```powershell
.\scripts\build.ps1 -Tasks ':touch:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug'
```

- `:touch:test`: **19개 통과**.
- `:app:testDebugUnitTest`: **81개 통과**.
- Debug 앱/기기 테스트 APK 빌드 성공.
- Android Lint: **0 errors, 11 warnings**. 경고는 기존 Manifest 속성, 의존성 버전, debug 진단 문구, 키보드 좌우 정렬 영역에 있다.

추가 검증은 프로필 분리·대표 배치 누적 이동 방지·미리보기의 저장/퇴출 방지·가중치·참고 감소와 초기화·드문 키 보존·v1/v2 저장·손상 및 정수 범위·한영 정답 경로·단일 인접 치환과 모호한 입력 제외·실제 사전의 26키 커버리지를 포함한다.

Robolectric 검증에는 토큰/필드 연결 제한, 조회 모드 무학습, 중복 완료, 초기화 전 대기 단어 제외, 저장 복원/실패 표시를 포함했다. 지도는 실제 키보드 geometry와 저장 후 재구성한 geometry의 판정을 격자점마다 비교한다. Windows Robolectric의 `Os.rename` 미구현을 보완하는 테스트 전용 AtomicFile shadow를 사용하며, Android의 실제 AtomicFile은 기기 테스트에서 검증한다.

## 80 Turbo 실기기 검증

- 기기: **iPlay80miniTurbo / Android 16 / API 36**.
- 명시적 serial: `T860128GB26388620671`.
- 잠금 해제 상태에서 실제 IME 창에 터치를 주입했다.
- 개인화 검증은 임시 fixture 저장소를 사용하고 기존 학습 데이터는 초기화하지 않았다. 앱 설정과 이전 IME·회전·활성 IME 목록을 복원한다.
- 유일한 IME를 APK 교체 후 재사용할 때 생기는 Android의 오래된 서비스 바인딩은 테스트 준비 단계에서 잠시 해제하고 복원하도록 스크립트를 보강했다.

### 회귀 검증: 16개 통과

[실행 로그](device-runs/20261108-123950-T860128GB26388620671/instrumentation.txt), [기기 정보](device-runs/20261108-123950-T860128GB26388620671/device.json).

신규 7개 시나리오:

- `learningDataTabsShowMatchingProfilesWithoutTraining`
- `guidedPracticeLearnsOnlyValidatedTouches`
- `layoutProfilesResumeAcrossGeometryChanges`
- `guidedPracticeStopsOnRotationAndEditorChange`
- `profileResetCannotRestorePendingSamples`
- `guidedEnglishCapturesEveryPhysicalKey`
- `guidedEvaluationUsesHeldOutWordsWithoutSaving`

기존 회귀 9개 시나리오:

- `spatialCorrectionIsOptInAndSkipsPasswordFields`
- `contextualTouchUsesPreviousWordWithoutChangingCenterTaps`
- `rotateWhileComposingAndSplitLayout`
- `splitKeysKeepProportionsAcrossRotationAndSymbolPages`
- `rapidInputDoesNotLoseOrDuplicateKeys`
- `rapidOverlappingTapsAndEdgeDriftInBothOrientations`
- `keyGapSlidersPreviewCancelResetAndPersistAcrossRotation`
- `hangulCompositionAndHistoryDeletion`
- `editorSwitchAndRestartDoNotDuplicateText`

조회와 집중 학습은 **한국어/영어 × 세로/가로 × 일반/스플릿**을 확인했다. 너비·높이·가로/세로 키 간격·스플릿 간격 변경, 이전 배치 복귀, 수정 단어 제외, 마지막 단어 확정, 회전/입력창 변경, 대기 표본 초기화, 평가 모드 무저장을 검증했다.

빠른 입력은 40회 터치/2,589ms(약 15.45회/초)에서 고정 한글 fixture 결과와 정확히 일치했다. [처리량 기록](device-runs/20261108-123950-T860128GB26388620671/screens/rapid-input.txt), [다중 포인터 기록](device-runs/20261108-123950-T860128GB26388620671/screens/overlapping-taps.txt). 주입 속도 기반 검사이며 사람의 입력 속도나 resolver p95 지연 측정값은 아니다.

### 최종 UI 확인

완료 키 제출·원시 관측 정리·프로필 표시를 반영해 아래 추가 검증도 통과했다. 기존 회귀 실행과 합쳐 **서로 다른 실기기 시나리오 17개**를 확인했다.

| 실행 | 결과 |
|---|---|
| [신규 기능 재검증](device-runs/20261108-124548-T860128GB26388620671/instrumentation.txt) | 7개 통과. 키보드 완료 키 제출과 고정 단어 평가 포함 |
| [최종 프로필 표시·조회](device-runs/20261108-125111-T860128GB26388620671/instrumentation.txt) | 2개 통과. 실제 키 영역 너비로 프로필 구별, 양방향 지도 및 배치 복귀 |
| [결과 요약·완료 키 최종 확인](device-runs/20261108-125309-T860128GB26388620671/instrumentation.txt) | 2개 통과. `learningSummarySurvivesProfileRefresh` 추가, 목록 갱신 후에도 저장/제외 요약 유지 |

실제 결과 화면을 열어 지도 글자 대비와 학습 요약 유지, 원문 입력란 정리를 확인했다.

- [최종 집중 학습 결과 화면](device-runs/20261108-125309-T860128GB26388620671/screens/learning-summary-final.png)
- [한국어 세로 조회 지도](device-runs/20261108-125111-T860128GB26388620671/screens/learning-map-ko-portrait-regular.png)
- [영어 가로 스플릿 지도](device-runs/20261108-125111-T860128GB26388620671/screens/learning-map-en-landscape-split.png)
- [학습 없는 영어 평가 결과](device-runs/20261108-124548-T860128GB26388620671/screens/guided-evaluation-en.png)

최종 APK는 `app/build/outputs/apk/debug/app-debug.apk`이며 기기에 설치했다. SHA-256:

```text
5385ED7AF7E17A896F516214625639308C7AF9AFE5FF80AFDA922C458C800650
```

마지막 실행 후 기본 IME `io.github.minjj.keyboard/.ime.MinjjImeService`, 활성 IME 목록, 자동 회전 `0`, 사용자 회전 `0`의 복원을 확인했다. 관련 변경 후 테스트를 선별 실행했으며 전체 기기 스위트와 릴리스 서명/배포 검증은 이번 범위에서 실행하지 않았다.

## 한계와 후속 검증

- 이번 검증은 실제 터치 전달·상태 격리·표본 채택·화면 동작을 확인한다. 사용자의 실제 오타율 개선은 측정하지 않았다.
- 과거 히트맵은 제공하지 않는다. 저장한 집계의 평균과 실제 보정 중심, 이번 세션의 일시적 터치 점을 구별한다.
- 연습은 보수적인 단어 단위 채택을 사용한다. 지원하지 못하는 경로·누락·모호한 수정은 부분 추정해 저장하지 않는다.
- 백스페이스 제한 도전 모드와 자동 손 자세 추정은 포함하지 않는다.
- TalkBack 실제 사용자 탐색, 다른 기기·OS, 장기간 손 타이핑에 의한 정확도 평가가 후속 과제다. 키별 정보는 별도 native 버튼과 접근성 설명으로 제공한다.
