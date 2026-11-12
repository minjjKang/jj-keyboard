# 집중 학습의 키보드 동시 표시 배치

2026-11-08 후속 요청에 따라 학습 데이터 확인 페이지를 제거하고 집중 학습 화면을 변경했다.

## 변경 내용

- 개인화 설정 메뉴의 `학습 데이터 확인` 진입점, 해당 페이지의 읽기 전용 실행 모드·제목·설명을 제거했다. 집중 학습과 그 안의 지도·키별 통계는 유지한다.
- 집중 학습에서 ScrollView를 제거했다. 키보드가 열리는 높이를 시스템 inset으로 반영하고 남은 화면 안에 입력과 지도를 배치한다.
- 학습 시작 시 언어·프로필 설정 영역과 저장 상태 설명을 접는다. 종료하면 해당 영역을 다시 표시한다.
- 세로: 위에 단어·입력·진행·동작 버튼, 아래에 개인화 지도.
- 가로: 왼쪽 42%에 입력과 동작, 오른쪽 58%에 지도. 남은 세로 공간을 두 영역이 공유한다.
- 지도는 사용 가능한 너비와 높이 중 작은 축에 맞춰 전체 문자 키 영역의 비율을 유지하고 중앙에 표시한다. 렌더와 지도 터치의 좌표 변환을 같은 영역에 맞췄다.
- 저장 상태만 바뀌는 경우 불필요한 재렌더를 막고, 같은 크기의 새 지도를 계산하는 동안 기존 지도를 유지한다. 렌더 완료 여부는 최신 계산 세대 기준으로 확인한다.

## 검증

빌드와 Android Lint:

```powershell
.\scripts\build.ps1 -Tasks ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug'
.\scripts\build.ps1 -Tasks ':app:testDebugUnitTest', '--tests', 'io.github.minjj.keyboard.correction.PersonalizationIntegrationTest'
```

- 앱/테스트 APK 빌드 성공.
- Android Lint: 0 errors, 기존 영역 경고 11개.
- 관련 JVM 테스트 5개 통과. 픽셀 판정·간격·소수 배율·연결 제한·표본 저장/초기화·복원·저장 실패 검증 포함.

80 Turbo: iPlay80miniTurbo, Android 16/API 36, serial `T860128GB26388620671`.

| 실행 | 결과 |
|---|---|
| [관련 시나리오 4개](device-runs/20261108-140333-T860128GB26388620671/instrumentation.txt) | 화면 배치, 결과 요약, 입력 연결/회전 중단, 키보드 완료 키 제출 통과 |
| [최종 APK 재검증 2개](device-runs/20261108-140717-T860128GB26388620671/instrumentation.txt) | 8개 화면 조합의 배치·렌더와 실제 입력 재확인 통과 |

`guidedPracticeFitsAboveImeInBothOrientations`는 한국어/영어 × 세로/가로 × 일반/스플릿의 8개 조합에서 다음을 확인한다.

- 메뉴에 제거한 페이지가 없음.
- 학습 화면에 ScrollView/HorizontalScrollView가 없음.
- 지도·입력란·단어·완료·다시 입력·건너뛰기·종료 버튼이 부모에 잘리지 않고 전부 보임.
- 각 영역의 전체 화면 경계가 실제 IME 위에 위치함.
- 가로에서는 입력란 오른쪽에 지도, 세로에서는 입력란 아래에 지도가 위치함.
- 전체 문자 키 지도가 할당된 영역 안에 들어가며 렌더 높이가 72dp 이상임.
- 실제 터치로 입력한 단어가 저장되고 키보드 완료 키로 다음 단어로 진행함.

실제 캡처도 열어 두 방향의 입력 영역·지도·키보드가 함께 보이는 것을 확인했다. 기존 IME·활성 IME 목록·회전·앱 설정은 검증 후 복원했다. 이번 검증은 80 Turbo의 기본 글자 배율과 테스트한 레이아웃을 기준으로 한다.

## 화면과 APK

- [가로 한국어 스플릿](device-runs/20261108-140717-T860128GB26388620671/screens/training-with-ime-ko-landscape-split.png)
- [가로 영어 일반](device-runs/20261108-140717-T860128GB26388620671/screens/training-with-ime-en-landscape-regular.png)
- [세로 영어 일반](device-runs/20261108-140717-T860128GB26388620671/screens/training-with-ime-en-portrait-regular.png)
- [정리된 개인화 메뉴](device-runs/20261108-140717-T860128GB26388620671/screens/personalization-groups-portrait.png)

최종 Debug APK를 기기에 설치했다. 파일: `app/build/outputs/apk/debug/app-debug.apk`.

SHA-256: `BDA4536078D80FFFD532D04CC3393127616649922A5E430170D29FDCC0384D04`.
