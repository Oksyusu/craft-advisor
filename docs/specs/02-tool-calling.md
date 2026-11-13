# 사양서 02: 도구 호출 방식 (AI가 JEI 조회 도구를 직접 호출)

> 상태: **초안 (승인 대기)**. 승인 전에는 구현하지 않는다.

## 메타데이터
- 작성일: 2026-11-13
- 출처: `docs/feature-research.md` 2절 (원본: Viper LLM)
- 작업량 추정: 중간
- **선행 사양: 03.** 도구 결과의 아이템 식별은 03에서 바꾸는 키(JEI 고유 ID 기준)를 쓴다.
- **함께 설계:** 01(도구 결과에도 공유 설정 적용), 05(도구 호출을 제공자 중립 형식으로 표현)
- 배정된 한계:
  - 자유 질문은 Claude가 기억만으로 답함(근거 없음)
  - 이름 검색이 단순 글자 포함이라 "아이언 비" → "Iron Bee"를 못 찾음
  - JEI의 아이템만 다룸(유체·엔티티 검색 부분)
  - 시스템 프롬프트가 짧아 캐시가 걸리지 않을 가능성

## 목표
자유 질문에서도 "계산은 코드, 판단은 AI" 원칙을 지킨다. AI는 실제 게임 데이터를 도구로 조회한 뒤에만 레시피와 숫자를 말한다. 검색어를 바꿔 가며 다시 찾을 수 있어서 후보 검색 실패가 줄어든다.

## 현재 상태 (코드 기준)
- `AdvisorFlow.start`: 정확한 이름 → 계산 / 후보 있음 → `pickItem` / 후보 없음 또는 `intent=question` → `askFreeQuestion`
- `askFreeQuestion` → `ClaudeProvider.chat(history, question, inventorySummary)`: 도구 없이 한 번 호출
- `ItemCandidates.score`: 현재 언어 이름과 등록 ID에 대한 글자 포함 검색. 띄어쓰기 차이("기계식피스톤")와 다른 언어 이름("Iron Bee")을 못 찾는다
- `ClaudeProvider.textOf`: text 블록만 모으고 thinking 블록은 버림

## 범위
### 1. 적용 경로
- **자유 질문 경로를 도구 호출 반복 구조로 바꾼다.** `askFreeQuestion`이 이 구조를 쓴다.
- 목표 아이템 경로(정확한 이름, `pickItem` → 계산 → `advise`)는 **그대로 둔다.** 빠르고(effort low) 이미 근거 있는 계산 결과를 쓰기 때문이다.
- `pickItem`이 `question`을 돌려주면 지금처럼 자유 질문 경로로 넘어가므로, "아이언 비" 같은 검색 실패도 도구 경로에서 다시 찾게 된다.

### 2. 도구 4개
모든 도구는 03의 키 문자열(`uid`)로 재료를 가리킨다. 결과는 압축된 JSON이고 크기 상한이 있다.

| 도구 | 입력 | 결과 | 상한 |
|---|---|---|---|
| `search_items` | `query` | `[{uid, kind, name, nameEn, hasRecipe}]`. `kind`는 item·fluid·기타 JEI 재료 종류 | 15개 |
| `get_recipes` | `uid` | 그 재료를 만드는 레시피 요약: `[{category, machine, inputs:[{name, uid, amount, alternatives}], outputs:[...]}]`. 대체 재료는 앞 3개와 전체 개수만 | 레시피 5개 |
| `get_inventory` | 없음 | 많은 순 `[{uid, name, count}]` + 전체 종류 수 | 40종. **`shareInventory=false`면 도구 자체를 제공하지 않음(01)** |
| `compute_missing` | `uid`, `amount`(1~999) | `CraftPlanner` 결과를 지금 `planJson`과 같은 형식으로. **숫자는 코드 결과 그대로** | 제작 단계 30개. `shareInventory=false`면 빈 인벤토리 기준(01) |

- 도구 실행 코드는 지금 있는 `ItemCandidates`, `JeiRecipeSource`, `Items`, `CraftPlanner`를 감싼다.
- `get_recipes`는 계산기가 다루지 못하는 비아이템 재료(유체, Productive Bees의 벌)도 **읽기 전용으로** 보여 준다. 계산에 반영하는 것은 03의 범위다.

### 3. 검색 개선 (`ItemCandidates` → 순수 Java `NameMatcher` 분리)
- 띄어쓰기를 무시한 비교를 추가한다("기계식피스톤" = "기계식 피스톤").
- **영어 이름 색인:** 게임 언어가 영어가 아닐 때 `en_us` 언어 파일을 한 번 읽어 아이템의 영어 이름을 함께 검색한다. 정확한 이름 검색(AI 없는 경로)에도 적용되어 `/ai Mechanical Piston`이 한국어 클라이언트에서도 바로 찾아진다.
- 등록 ID의 `_`를 띄어쓰기로 본다(`iron_bee` ↔ "iron bee").
- 검색 대상에 JEI의 비아이템 재료 종류도 넣는다(현재 언어 표시 이름만. 영어 이름은 아이템에만).
- `NameMatcher`는 Minecraft 없이 단위 테스트한다.

### 4. 반복 구조 (`ToolLoop`, 제공자 중립, 순수 Java)
```
질문 → provider.converse(메시지, 도구) ─┬─ 최종 답 → 채팅
                                       └─ 도구 호출들 → [게임 스레드에서 실행] → 결과를 메시지에 붙여 다시 converse
```
- **스레드:** JEI 조회는 게임 스레드에서만 한다. 도구 실행은 `Minecraft::execute`로 넘기고 `CompletableFuture`로 결과를 받는다. 게임 스레드도, AI 스레드도 기다리며 막지 않는다(future를 이어 붙임).
- **상한:** 질문 하나당 도구 호출 8회(`maxToolCalls` 설정, 1~20). 상한에 닿으면 다음 요청은 도구를 끄고(`tool_choice: none`) 지금까지의 결과로 답하게 한다.
- 한 응답에 도구 호출이 여러 개면 순서대로 모두 실행하고 결과를 한 번에 붙인다.
- 알 수 없는 도구 이름, 잘못된 입력, 없는 `uid`는 오류 결과(`is_error`)로 돌려주고 반복을 계속한다(AI가 고칠 기회).
- 채팅에는 도구 호출 과정을 보여 주지 않는다. "생각 중" 안내와 최종 답만 나온다. 도구 이름과 입력은 DEBUG 로그에만 남긴다.
- 대화 기록(`HISTORY`)에는 질문과 최종 답만 저장한다(도구 결과는 저장하지 않음).

### 5. `AiProvider` 인터페이스 (05가 따를 형식)
```java
CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled);

record ToolSpec(String name, String description, JsonObject inputSchema) {}
record ToolCall(String id, String name, JsonObject input) {}
record ToolResult(String callId, String content, boolean isError) {}
sealed interface AgentMessage {
    record User(String text)
    record Assistant(String text, List<ToolCall> calls, JsonElement providerRaw)  // providerRaw: 제공자 원본 응답 블록
    record ToolResults(List<ToolResult> results)
}
record AgentReply(AgentMessage.Assistant message, boolean wantsTools) {}
```
- `providerRaw`를 두는 이유: Claude는 도구 반복 중 **assistant 응답 블록(thinking 포함)을 그대로 돌려보내야** 한다. OpenAI 호환도 `tool_calls` 원본을 돌려보낸다. 제공자마다 원본을 보관하고, 루프는 내용을 몰라도 된다.
- 기존 `chat()`은 `converse()`로 대체하고 삭제한다. `pickItem`, `advise`는 그대로.

### 6. Claude 요청 형식
- 요청에 `tools: [{name, description, input_schema}]`, 응답의 `tool_use` 블록과 `stop_reason: "tool_use"`를 처리하고, 다음 요청에 `tool_result` 블록을 user 메시지로 붙인다.
- effort는 medium. `max_tokens`로 끊긴 응답은 `BAD_RESPONSE` 오류로 처리한다.
- **캐싱:** 도구 정의와 길어진 시스템 프롬프트 끝에 `cache_control`을 둔다. 응답의 `usage.cache_read_input_tokens`로 실제로 캐시가 걸리는지 확인한다(완료 기준 6).

### 7. 프롬프트 (`Prompts.CHAT` 교체)
- 레시피, 재료, 개수는 **도구 결과에 있는 것만** 말한다. 확인하지 못했으면 모른다고 말하고 `/ai <아이템 이름>`을 안내한다.
- 검색 결과가 없으면 영어 이름이나 다른 표현으로 다시 검색한다.
- 답은 질문 언어로, 6줄 이내, 마크다운 없음(지금과 같음).

## 범위에서 빼는 것
- AI가 JEI 화면을 열거나 클립보드를 쓰는 부작용 도구(아래 결정 필요 1)
- 목표 아이템 경로를 도구 방식으로 합치기
- 작은 로컬 모델의 형식 오류 복구 → 05

## 완료 기준
- [ ] 1. 단위 테스트(`ToolLoop`, 가짜 제공자): 도구 호출 → 결과 → 최종 답 흐름, 한 응답 안의 여러 호출, 상한 도달 시 도구 끄고 마무리, 잘못된 도구 이름에 오류 결과.
- [ ] 2. 단위 테스트: `compute_missing` 결과의 숫자가 같은 입력의 `CraftPlanner.plan` 결과와 같다.
- [ ] 3. 단위 테스트(`NameMatcher`): "기계식피스톤", "Mechanical Piston"(영어 색인), "iron bee"(ID 밑줄)가 각각 기대한 항목을 찾는다.
- [ ] 4. 단위 테스트: `shareInventory=false`면 도구 목록에 `get_inventory`가 없다(01과 연결).
- [ ] 5. 게임 확인: `/ai 안산암 합금은 어떻게 만들어?` → 답에 나온 재료가 JEI 레시피 화면과 일치한다. DEBUG 로그에 `get_recipes` 호출이 있다.
- [ ] 6. 게임 확인: 같은 자유 질문을 두 번 하면 두 번째 응답의 `cache_read_input_tokens`가 0보다 크다. 0이면 원인(최소 길이 미달 등)을 기록하고 이 기준은 "확인함"으로 닫는다.
- [ ] 7. 게임 확인(Productive Bees 설치 시): `/ai 아이언 비 어떻게 얻어?` → 도구로 벌을 찾아 근거 있는 답을 한다.
- [ ] 8. 도구 반복 중에도 게임이 멈추지 않는다.

## 위험
- **R1. 영어 이름 읽기:** 클라이언트에서 현재 언어와 별도로 `en_us`를 읽는 방법(`ClientLanguage.loadFrom` 등)이 1.21.1에서 쓸 수 있는지 먼저 확인한다. 안 되면 영어 색인을 빼고 도구 재검색에만 기댄다.
- **R2. 비아이템 재료 이름:** JEI 재료 종류마다 `IIngredientHelper.getDisplayName`이 의미 있는 이름을 주는지 확인한다(벌, 유체).
- **R3. 비용·응답 시간:** 도구 호출 1회마다 요청이 하나 늘어난다. 게임 확인 때 질문 하나당 호출 수와 걸린 시간을 기록한다.

## 결정 필요 (추천안 먼저)
1. **JEI 화면 열기 도구(`open_recipe`) 추가:** 추가하지 않음 추천. 자유 질문 답에서 아이템을 보고 싶으면 07의 클릭으로 연다. 추가하면 "아이언 비 만들고 싶어"가 화면까지 이어지지만, AI가 화면을 바꾸는 부작용이 생긴다.
2. **`maxToolCalls` 기본값:** 8 추천.
