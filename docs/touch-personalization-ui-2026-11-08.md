# 터치 개인화 화면 정리

2026-11-08 사용자 후속 요청에 따라 메뉴 그룹과 두 학습 화면을 정리했다.

이 기록 이후 학습 데이터 확인 페이지를 제거하고 집중 학습을 스크롤 없는 배치로 변경했다. 최신 화면은 [집중 학습 배치·검증 기록](focused-training-layout-2026-11-08.md)을 참조한다.

이후 지도 좌표·색칠 정합성 보완은 [픽셀 판정 검증 기록](touch-map-pixel-verification-2026-11-08.md)을 참조한다.

## 변경 내용

터치 위치 개인화 메뉴는 기존 설정과 같은 둥근 카드 세 개로 구성한다.

1. 터치 보정·평소 자동 학습·문맥 보정·단어 후보 설정.
2. 학습 데이터 확인·집중 학습 진입점.
3. 사전 출처 및 라이선스·전체 학습 데이터 초기화.

학습 데이터 확인과 집중 학습 화면에는 기존 설정의 카드·글자색·보조 설명·강조색 동작 스타일을 적용했다.

- 한국어/영어와 프로필 선택 유지.
- 하단의 26개 키 버튼, 선택 프로필 초기화/삭제, 레이아웃 설정 이동 버튼 제거.
- 개인화 판정 스위치 제거. 개인화 지도는 입력 중에도 숨기지 않으며 독립된 카드로 표시.
- 지도의 빈 툴바·숫자행·시스템 영역을 화면에서 잘라 문자 키 영역을 확대. 저장 좌표와 실제 판정 계산은 유지하고 지도 터치 선택 좌표만 화면 변환에 맞춤.
- 지도에서 키를 누르면 키별 집계를 확인. 제거한 별도 버튼 대신 지도 접근성 설명에도 전체 키별 통계를 제공.
- 프로필별 데이터 저장·자동 선택·복귀, 정답 대응, 학습/평가 정책은 유지. 전체 데이터 초기화는 개인화 메뉴 최하단에 제공.

## 검증

```powershell
.\scripts\build.ps1 -Tasks ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug'
.\scripts\device-tests.ps1 -DeviceSerial T860128GB26388620671 -SkipBuild -TestName learningDataTabsShowMatchingProfilesWithoutTraining,learningSummarySurvivesProfileRefresh,guidedEnglishCapturesEveryPhysicalKey
```

- 앱/테스트 APK 빌드 성공. Android Lint 0 errors, 기존 영역 경고 11개.
- 80 Turbo(iPlay80miniTurbo), Android 16/API 36에서 관련 시나리오 **3개 통과**.
- 조회 화면은 한국어/영어 × 세로/가로 × 일반/스플릿 확인. 입력 중 지도 표시 상태와 제거한 관리 버튼이 없음을 확인.
- 집중 학습 저장·결과 요약 유지·키보드 완료 키 제출 확인.
- 메뉴의 세 카드와 두 학습 화면의 실제 캡처를 열어 카드 배치·글자 대비·지도 크기를 확인.
- 기존 IME·활성 IME 목록·회전·앱 설정은 테스트 스크립트와 정리 단계에서 복원.

[실기기 실행 로그](device-runs/20261108-133228-T860128GB26388620671/instrumentation.txt)

## 화면

- [개인화 메뉴 세로](device-runs/20261108-133228-T860128GB26388620671/screens/personalization-groups-portrait.png)
- [개인화 메뉴 가로](device-runs/20261108-133228-T860128GB26388620671/screens/personalization-groups-landscape.png)
- [집중 학습 결과](device-runs/20261108-133228-T860128GB26388620671/screens/learning-summary-final.png)
- [한국어 조회 지도](device-runs/20261108-133228-T860128GB26388620671/screens/learning-map-ko-portrait-regular.png)
- [영어 가로 스플릿 지도](device-runs/20261108-133228-T860128GB26388620671/screens/learning-map-en-landscape-split.png)
