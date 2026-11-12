# 보조문자 및 특수문자 사용자 설정

- 설정 홈의 **스타일 및 레이아웃 → 보조문자 및 특수문자**에서 접근한다.
- 레이아웃 페이지의 **보조 문자 표시 및 길게 입력** 스위치를 새 페이지로 이동했다. 기존 설정 키를 그대로 사용하므로 사용자의 선택을 유지한다.
- **키별 보조문자 편집**: 한국어, 영어, 기호 1·2 자판에서 문자 키를 선택해 길게 입력할 값을 저장한다. 빈 값은 해당 키의 보조 입력을 끄고, 기본값은 사용자 지정 값을 제거한다. 숫자, 문장부호와 이메일·주소 입력용 `@`, `/` 키도 편집할 수 있다.
- **특수문자 키 편집**: 기호 1·2페이지의 문자 키를 변경한다. 일반 배열과 대화면 확장 배열은 별도로 편집하며 분할 여부와 화면 방향에 상관없이 같은 슬롯에 적용한다.
- 입력값은 이모지를 포함해 최대 8 Unicode 코드 포인트이며 공백·줄바꿈은 허용하지 않는다. 기능키와 숫자·전화번호 전용 자판은 변경하지 않는다.
- DataStore에 명시적인 사용자 지정 값만 저장한다. 기호 슬롯에 보조문자를 연결하므로 기본 문자를 바꿔도 보조문자가 유지된다. 회전 시 선택 자판·배열과 작성 중인 대화상자 내용을 복원한다.

## 검증

- 관련 JVM/Robolectric 테스트 33개 통과: `CharacterCustomizationTest` 4개, `SettingsScreenTest` 3개, `KeyboardViewTest` 26개.
- Debug APK와 계측 테스트 APK 빌드 성공. `:app:lintDebug`: 오류 0개, 경고 12개.
- 80 Turbo (`iPlay80miniTurbo`, Android 16 / API 36, `T860128GB26388620671`)에서 실제 터치 검증:
  - `characterSettingsEditorsPersistAndResetInBothOrientations`: 새 메뉴 진입, 두 편집 화면 저장·재열기·기본값 복원, 세로·가로 통과.
  - `characterEditorKeepsUnsavedDraftAcrossRotation`: 저장 전 문자를 입력하고 회전한 후 복원·저장 통과.
  - `customCharactersTypeInAllKeyboardsAcrossRotation`: 한글·영문 보조문자, 기호 기본 입력의 이모지 변경, 기호 보조 입력, 보조 입력 끄기, 세로·가로 통과.
  - 기존 `samsungSymbolPagesInBothOrientations`, `rapidOverlappingTapsAndEdgeDriftInBothOrientations` 통과.
- 테스트는 완료 후 원래 설정, 입력기와 회전 설정을 복원한다.

## 실행 기록

- [최종 설정 UI·회전 테스트](device-runs/20261109-001204-T860128GB26388620671/instrumentation.txt): 2개 모두 통과.
- [문자 입력·기호·빠른 터치 검증](device-runs/20261109-000809-T860128GB26388620671/instrumentation.txt): 해당 3개 통과. 같은 실행의 첫 설정 UI 테스트는 하위 화면 전환 대기를 보완한 후 위 최종 실행에서 통과했다.
- 중간 회전 테스트는 대화상자 표시 중 Activity 포커스를 요구하던 테스트 조건을 수정했다. 작성 중인 문자는 실제로 유지되었으며 최종 실행에서 저장까지 확인했다.

![보조문자 편집 · 세로](device-runs/20261109-001204-T860128GB26388620671/screens/character-secondary-portrait.png)

![특수문자 키 편집 · 가로](device-runs/20261109-001204-T860128GB26388620671/screens/character-symbol-landscape.png)
