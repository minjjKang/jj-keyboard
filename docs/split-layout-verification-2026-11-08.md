# 분할 QWERTY 배치 수정 검증

기준은 [삼성 분할 자판 캡처](reference/samsung-tab-s9plus-android16/29-split-keyboard.png)다. 문자 순서는 `QWERT / YUIOP`, `ASDFG / HJKL`, `ZXCV / BNM`을 유지한다. 사용자 요청에 따라 확장형의 왼쪽 A행 들여쓰기는 기본형·세로 분할형과 같은 0.5u로 조정했다. Z행은 약 한 칸 반 들여쓰며, 오른쪽 H행은 Y행보다 안쪽으로 들어간다.

분할 문자 키 너비 계산에서 공백 키 행을 제외했다. 양쪽 문자 묶음은 바깥쪽에 정렬하고, 기능키는 문자 키 너비에 비례하도록 고정했다. 남는 폭은 하단 공백 키가 채운다. 확장 기호 페이지의 분할 경계도 문자 페이지와 맞췄다.

검증:

아래 결과는 최초 배치 수정 기준이다. 후속 A행 0.5u 변경은 사용자 요청으로 테스트를 생략하고 debug APK 빌드·설치만 수행했다.

- `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` 통과.
- Robolectric에서 세로 600dp·가로 960dp, 기본형·확장형, 일반·이메일·URL 입력, 문자·기호 2개 페이지의 키 비율·영역 경계·공백 키 폭과 QWERTY 들여쓰기 확인.
- iPlay80miniTurbo / Android 16 / API 36 / `T860128GB26388620671`에서 분할 비율과 회전, 조합 중 회전, 빠른 연속 입력, 중앙 빈 공간 무입력 등 4개 시나리오 통과. [기기 실행 기록](device-runs/20261108-011523-T860128GB26388620671/instrumentation.txt).
- 한영·양방향 연속 멀티터치/경계 드리프트도 [단독 재실행 통과](device-runs/20261108-011645-T860128GB26388620671/instrumentation.txt). 첫 실행에서는 접근성 좌표와 실제 화면 좌표가 불일치했다. 새 배치 테스트가 기호 페이지에서 Q 키를 찾던 문제는 숫자 키를 기준으로 검사하도록 수정했다.

![가로 확장형](device-runs/20261108-011523-T860128GB26388620671/screens/split-balanced-extended-landscape-0.png)

![세로 기본형](device-runs/20261108-011523-T860128GB26388620671/screens/split-balanced-standard-portrait-0.png)
