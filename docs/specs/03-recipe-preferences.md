# 사양서 03: 대체 재료 선택 + 레시피 선호 규칙 (+ 재료 식별 방식 변경)

> 상태: **초안 (승인 대기)**. 승인 전에는 구현하지 않는다.

## 메타데이터
- 작성일: 2026-11-13
- 출처: `docs/feature-research.md` 3절 (원본: JEI unofficial)
- 작업량 추정: 중간
- 선행 사양: 없음. **02와 04보다 먼저 한다**(둘 다 이 사양의 식별 방식에 의존)
- 배정된 한계:
  - `ItemKey`가 등록 ID만 써서 종류 데이터로 나뉜 변형이 하나로 합쳐짐
  - JEI의 아이템만 다룸. 유체·엔티티 미지원(계산 부분)

## 목표
1. 재료를 **JEI가 구분하는 방식대로** 식별해서, 변형(물약, 마법 부여 책, 종류 데이터로 나뉜 모드 아이템)이 섞이지 않게 한다.
2. 아이템이 아닌 재료(유체, 벌)가 계산에서 **조용히 빠지지 않고** 부족 목록에 나오게 한다.
3. 플레이어가 레시피와 대체 재료를 **규칙 파일로 지정**할 수 있게 한다.
4. 계산 결과에 어느 대안을 왜 골랐는지 보여 준다.

## 현재 상태 (코드 기준)
- `ItemKey(String id)`: 등록 ID만. `Items.keyOf`가 `BuiltInRegistries.ITEM.getKey`를 쓰므로 데이터 컴포넌트가 다른 변형이 같은 키가 된다.
- `JeiRecipeSource.toOption`: 유체 같은 비아이템 입력 칸은 `continue`로 **건너뛴다.** Create 혼합(용암 + …) 같은 레시피에서 필요한 유체가 결과에 없다.
- `CraftPlanner.choose`: "인벤토리로 충족되는 칸이 많은 레시피, 같으면 JEI 순서" 고정.
- `IngredientSlot.primary()`: 아무것도 없을 때 항상 첫 번째 대안을 푼다.
- `RecipeOption`에 레시피 종류(카테고리) 정보가 없다.

## 범위
### 1. 재료 키 (`ItemKey` → `IngredientKey`)
```java
public record IngredientKey(String kind, String uid) {
    static IngredientKey item(String uid)   // kind = "item"
}
```
- `kind`: JEI 재료 종류 ID(`item`, `fluid`, 모드가 등록한 종류).
- `uid`: JEI의 고유 ID(`IIngredientHelper.getUid`). 하위 종류가 없는 아이템은 등록 ID와 같다.
- `planner` 패키지는 여전히 Minecraft·JEI에 의존하지 않는다(문자열만).
- 기존 테스트의 `ItemKey.of("minecraft:piston")`는 `IngredientKey.item(...)`으로 기계적으로 바꾼다.
- `Items.keyOf(stack)`, `countAll`은 JEI 도우미를 써서 같은 규칙으로 키를 만든다. **인벤토리와 레시피가 같은 규칙으로 키를 만들어야** 매칭된다.
- 표시 이름은 계속 기본 이름(모루로 바꾼 이름 아님)을 쓴다(01의 개인정보 규칙).

### 2. 비아이템 재료
- `JeiRecipeSource`는 비아이템 입력 칸도 `IngredientSlot`으로 넣는다. 개수는 JEI 양 그대로(유체는 mB).
- `CraftPlanner`는 `kind != item`인 재료를 **풀지 않고** 부족 목록에 넣는다. 새 `StopReason.NOT_ITEM`.
- 인벤토리로 충족하지 않는다(양동이 등은 이번 범위 밖).
- 채팅과 클립보드에는 단위를 붙인다: 유체는 "1000 mB", 기타는 개수.

### 3. 선호 규칙 파일 `config/craftadvisor/recipe-preferences.txt`
```
# 줄마다 규칙 하나. '#' 뒤는 주석.
# recipe <만들 재료 uid> <레시피 종류 uid>  : 이 재료는 이 종류의 레시피로 만든다
recipe create:andesite_alloy create:mixing
# prefer <재료 uid>                         : 이 재료가 대안에 있는 칸에서는 이것을 고른다
prefer minecraft:spruce_planks
```
- 파일이 없으면 첫 실행 때 주석만 있는 예시 파일을 만든다.
- `/ai` 실행마다 파일을 다시 읽는다(작고 가벼움, 다시 읽기 명령 불필요).
- 잘못된 줄은 건너뛰고, 줄 번호와 이유를 채팅에 **한 번만** 경고하고 로그에 남긴다.
- 비아이템 재료는 `fluid:minecraft:water`처럼 `kind:` 접두어로 적는다(아이템은 생략).
- 파서는 순수 Java로 만들고 단위 테스트한다.

### 4. 계산기 선택 순서
**레시피 고르기** (재료마다):
1. `recipe` 규칙이 있고, 그 종류의 레시피가 쓸 수 있으면(순환 제외 후) 그것. 여러 개면 아래 2·3으로 고른다.
2. 인벤토리로 충족되는 칸이 많은 레시피 (현재 규칙)
3. JEI 순서 (현재 규칙)
- 규칙에 맞는 레시피가 없거나 순환으로 빠지면 2·3으로 돌아가고, 그 사실을 결과에 남긴다.

**대체 재료 고르기** (칸마다, 인벤토리에서 꺼내고도 모자랄 때 무엇을 풀지):
1. 인벤토리에 하나라도 있는 대안 → 지금처럼 먼저 쓴다(변경 없음)
2. `prefer` 규칙에 있는 대안 (규칙 파일 순서)
3. 첫 번째 대안 (현재 규칙)

- `RecipeOption`에 `category`(레시피 종류 uid) 필드를 추가한다.
- `CraftPlan`에 `choices: List<Choice>`를 추가한다: `Choice(slotOwner, chosen, alternativeCount, reason)`, `reason`은 `RULE`·`OWNED`·`DEFAULT`·`RULE_UNUSABLE`. 대안이 2개 이상인 칸과 `recipe` 규칙이 적용·실패한 재료만 기록한다.

### 5. 결과 표시
- 채팅 부족 목록 아래에 "고른 대안" 줄을 최대 5개 보여 준다. 예: `참나무 판자 (대안 9종 중 첫 번째)`, `안산암 합금: 혼합 레시피 (규칙)`.
- `DEFAULT`로 고른 것이 있으면 "규칙 파일로 바꿀 수 있음" 안내를 한 줄 붙인다(파일 경로 포함).
- 조언 JSON(`planJson`)에 `choices`를 넣어 AI가 다른 대안을 제안할 수 있게 한다.
- 클립보드는 지금처럼 부족 재료만 적는다.

## 범위에서 빼는 것
- 마우스 휠로 JEI 화면에서 고르기(JEI unofficial 방식). JEI 공식 API에 그 지점이 없다
- 채팅 명령으로 규칙 추가(`/ai prefer …`). 필요해지면 따로 추가
- 즐겨찾기·북마크 연동
- 유체 재료를 레시피로 더 풀기(예: 용암을 만드는 레시피 따라가기)

## 완료 기준
- [ ] 1. 기존 단위 테스트 14개가 키 변경 후에도 통과한다.
- [ ] 2. 단위 테스트: 같은 등록 ID에 uid가 다른 두 재료(물약 두 종류처럼)가 인벤토리와 부족 목록에서 따로 센다.
- [ ] 3. 단위 테스트: 유체 입력이 있는 레시피에서 유체가 `NOT_ITEM` 부족 재료로 양과 함께 나온다.
- [ ] 4. 단위 테스트: `recipe` 규칙이 기본 선택(충족 칸 수)보다 먼저 적용된다. 규칙 레시피가 순환이면 기본 선택으로 돌아가고 `RULE_UNUSABLE`이 기록된다.
- [ ] 5. 단위 테스트: `prefer` 규칙이 첫 번째 대안보다 먼저, 인벤토리에 있는 대안보다는 나중에 적용된다.
- [ ] 6. 단위 테스트(파서): 정상 줄, 주석, 빈 줄, 모르는 명령, 인자 개수 오류, `fluid:` 접두어.
- [ ] 7. 게임 확인: 물약이나 마법 부여 책이 재료인 레시피에서 변형이 섞이지 않는다.
- [ ] 8. 게임 확인: Create 혼합 레시피(안산암 합금 등)에서 유체가 필요하면 부족 목록에 mB로 나온다.
- [ ] 9. 게임 확인: 규칙 파일에 `prefer minecraft:spruce_planks`를 넣고 판자가 필요한 아이템을 계산하면 가문비나무 판자 기준으로 나온다.

## 위험
- **R1. JEI 고유 ID 형식:** JEI 19.x에서 `IIngredientHelper.getUid`의 정확한 시그니처와 `UidContext`, 하위 종류가 있는 아이템의 uid 형식을 먼저 확인한다. 인벤토리 스택과 레시피 칸 스택이 같은 uid를 내는지가 핵심이다.
- **R2. uid로 스택 되돌리기:** JEI 화면 열기(`IRecipesGui.show`)와 이름 표시에 키 → 재료 객체가 필요하다. `IIngredientManager`에서 uid로 찾는 방법(`getIngredientByUid` 등)을 확인하고, 없으면 검색 때 만든 색인에서 찾는다.
- **R3. 태그 칸의 의미:** 하위 종류가 있는 아이템이 태그 칸에 대안으로 들어갈 때, 인벤토리의 다른 변형이 그 칸을 충족하는지는 JEI 대안 목록에 그 변형이 있는지로만 판단한다(현재 규칙 유지).

## 결정 필요 (추천안 먼저)
1. **이름 변경 `ItemKey` → `IngredientKey`:** 바꾸기 추천(유체와 벌이 들어오면 "Item"이 틀린 이름). 바꾸지 않으면 diff는 작아지지만 이름이 오해를 부른다.
2. **규칙 파일 위치:** `config/craftadvisor/recipe-preferences.txt` 추천(연구 문서와 같음). 기존 설정 파일이 `config/craftadvisor-client.toml`이므로 폴더를 새로 만든다.
