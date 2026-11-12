# 자동 검증 기록

현재 검증은 [0.4.0 실기기·화면 검증](verification-0.4.0-2026-11-05.md)을 참조합니다. 이전 기록은 [0.3.0·0.3.1 삼성 레퍼런스 비교](samsung-reference-2026-11-05.md)와 [0.2.0 실기기 기록](device-verification-2026-11-05.md)에 보존합니다. 아래 내용은 0.1.0의 이전 자동 검증 기록입니다.

기준일: 2026-11-04 (Asia/Seoul). 버전: 0.1.0 개발용 디버그 빌드.

## 실행 결과

Windows에서 프로젝트 내부 JDK 17·Android SDK를 사용해 다음을 실행했습니다.

```powershell
.\scripts\build.ps1
```

| 검사 | 결과 |
|---|---|
| `:hangul:test` | 8개 통과 |
| `:app:testDebugUnitTest` | 16개 통과, Robolectric API 35 |
| `:app:assembleDebug` | 성공 |
| `:app:lintDebug` | 오류 0개, 의존성 업데이트 알림 3개 |
| `apksigner verify --verbose` | APK v2 서명 검증 성공 |
| `aapt dump permissions` | 요청 권한 없음 |
| `adb devices -l` | 연결 기기 없음 |

총 24개의 테스트 메서드가 통과했습니다. 한글 테스트 한 개에서 현대 한글 11,172개 음절의 조합·분해와 Java Unicode NFC 결과를 전부 비교합니다. 이것을 11,172개의 별도 입력기 통합 테스트로 계산하지 않습니다.

편집기 테스트에는 받침 이동 후 조합 영역 대체, 선택 대체/삭제, 커서 이동, 지연된 선택 콜백, 초기 커서 위치 미제공, 쓰기 실패 후 재시도 차단, 조합 종료, 이모지·결합 문자 삭제, Enter 동작이 포함됩니다. 서비스 테스트는 같은 연결의 구성 변경에서 조합 객체와 `간 → 가나` 입력을 유지하고, 다른 입력창에는 이전 조합을 전달하지 않는지 확인합니다. View 테스트는 280·411·800·1200px 폭에서 기능 키 크기와 접근성 Shift 클릭을 확인합니다.

Lint의 남은 3개 경고는 고정한 DataStore·Coroutines·Robolectric보다 새 버전이 있다는 알림입니다. Android 10 지원 분기의 이전 insets API 등은 컴파일러에서 deprecated 경고가 발생할 수 있습니다.

## 산출물

`app/build/outputs/apk/debug/app-debug.apk`

SHA-256:

```text
65f08e288cafe7714dfabb298005e257451dcfae127bbcd8a39862b4747a89fb
```

이 APK는 개발용 인증서로 서명했습니다. 공개 배포용 서명과 Play 제출 준비는 수행하지 않았습니다.

## 검증 한계

Robolectric은 실제 Samsung/Android의 IME 창, 원격 편집기, 폴더블 힌지나 TalkBack을 재현하지 않습니다. 실기기에서의 화면 전환 무손실, 다중 터치 정확성, 접근성, 지연 시간, 앱별 호환성은 아직 통과 판정을 내리지 않았습니다. 실행할 시나리오는 `device-test-matrix.md`에 기록했습니다.
