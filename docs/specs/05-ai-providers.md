# 사양서 05: AI 제공자 확장 (OpenAI 호환, Ollama, 오프라인)

> 상태: **초안 (승인 대기)**. 승인 전에는 구현하지 않는다.

## 메타데이터
- 작성일: 2026-11-13
- 출처: `docs/feature-research.md` 5절 (원본: PAIA)
- 작업량 추정: 중간
- **선행 사양: 02** (도구 호출 형식 `converse`/`ToolSpec`을 02에서 정한다). 01의 동의 규칙도 확장한다.
- 시점: 친구에게 배포하기 직전 (연구 문서 추천 순서)

## 목표
Anthropic 키가 없는 사람도 AI 기능을 쓸 수 있게 한다. OpenAI 호환 API, 로컬 Ollama, AI 없이 쓰는 오프라인 모드를 설정 하나로 고른다.

## 고정 결정 (다시 논의하지 않음)
- 모든 제공자는 **JDK `HttpClient` + Gson으로 직접 호출**한다. 공식 SDK를 넣지 않는다.
- Anthropic 기본 모델은 `claude-opus-5-5`.
- 계산은 코드, 판단은 AI. 제공자가 바뀌어도 같다.

## 현재 상태 (코드 기준)
- `AiProvider` 인터페이스와 `ClaudeProvider` 구현 하나. `AdvisorFlow.provider()`가 `apiKey`가 있으면 `ClaudeProvider`를 만든다.
- 설정: `apiKey`, `model`, `maxCandidates`, `adviceMaxTokens`.
- 키가 없으면 정확한 이름 검색 + 계산 + 클립보드만 동작한다(사실상 오프라인 모드).

## 범위
### 1. 설정 (`ClientConfig`)
| 키 | 기본값 | 의미 |
|---|---|---|
| `provider` | `"anthropic"` | `anthropic` · `openai` · `ollama` · `offline` |
| `baseUrl` | `""` | 비우면 제공자 기본값: anthropic `https://api.anthropic.com/v1`, openai `https://api.openai.com/v1`, ollama `http://localhost:11434/v1` |
| `apiKey` | `""` | 기존 그대로. ollama는 비어도 된다 |
| `model` | `"claude-opus-5-5"` | 제공자가 anthropic이 아닌데 이 값이면 "모델 이름을 설정하세요" 오류 |

- 기존 사용자 설정 파일은 그대로 동작해야 한다(`provider` 없음 = anthropic).

### 2. 구현 두 개
| 클래스 | 대상 |
|---|---|
| `ClaudeProvider` (기존) | Anthropic Messages API. `baseUrl` 지원만 추가 |
| `OpenAiCompatibleProvider` (신규) | `POST {baseUrl}/chat/completions`. OpenAI, Ollama의 OpenAI 호환 엔드포인트, 그 밖의 호환 서버 |

- **Ollama는 별도 구현 없이** `OpenAiCompatibleProvider` + 기본 주소 + 키 없음으로 처리한다(Ollama가 `/v1/chat/completions`를 제공). 네이티브 `/api/chat`이 필요해지면 그때 추가한다.
- 공통 부분(HTTP 클라이언트, 단일 데몬 스레드, 재시도, 상태 코드 → `AiException.Kind` 변환)은 두 구현이 공유하도록 뽑는다. 단, 지금 `ClaudeProvider`에만 있는 기능(effort, `fallbacks`, beta 헤더, `cache_control`)은 Claude에만 남는다.

### 3. OpenAI 호환 형식 대응
| 기능 | 요청 | 응답 |
|---|---|---|
| 아이템 고르기(`pickItem`) | `response_format: {type: "json_schema", json_schema: {...}}` | `choices[0].message.content`를 JSON으로 읽음 |
| 조언(`advise`) | 일반 메시지 | `content` |
| 도구 호출(`converse`) | `tools: [{type: "function", function: {name, description, parameters}}]`, 끌 때 `tool_choice: "none"` | `message.tool_calls[{id, function: {name, arguments(문자열 JSON)}}]`, 결과는 `role: "tool", tool_call_id` 메시지 |

- 02의 `AgentMessage.Assistant.providerRaw`에 `message` 원본(JSON)을 넣어 다음 요청에 그대로 돌려보낸다.
- 시스템 프롬프트는 `role: "system"` 메시지로 보낸다. `Prompts`는 공유한다.

### 4. 형식 오류 복구 (로컬 모델 대비)
- `pickItem` 응답이 JSON이 아니거나 스키마에 안 맞으면: 오류 내용을 붙여 **한 번만** 다시 요청한다. 또 실패하면 `intent=question`으로 보고 자유 질문 경로로 넘긴다(지금 `onPicked`의 "모르는 ID" 처리와 같은 방향).
- 도구 호출 `arguments`가 JSON이 아니면 02의 규칙대로 오류 결과(`is_error`)를 돌려주고 반복을 계속한다. 도구 호출 상한(02)이 무한 반복을 막는다.
- 제공자가 `response_format`이나 `tools`를 지원하지 않아 400을 돌려주면 "이 모델은 도구 호출을 지원하지 않습니다" 같은 이해하기 쉬운 오류를 보여 준다. 계산과 클립보드는 계속 동작한다.

### 5. 오프라인 모드 (`provider = offline`)
- 네트워크 호출을 전혀 하지 않는다.
- 동작: 정확한 이름·ID 검색(02의 영어 이름 포함) → 계산 → JEI 화면 → 채팅·클립보드. 04의 목록 기능도 정확한 이름으로 동작한다.
- 이름을 못 찾으면 비슷한 후보 5개를 채팅에 보여 준다(07이 있으면 클릭 가능). 자유 질문은 "오프라인 모드에서는 자유 질문을 쓸 수 없어요"로 답한다.
- `anthropic`·`openai`인데 키가 비어 있으면 지금처럼 같은 동작 + "키를 넣으면 AI 기능을 쓸 수 있어요" 안내.

### 6. 보안과 개인정보
- 키는 어떤 제공자에서도 로그·채팅에 나오지 않는다. 오류 로그에는 상태 코드와 오류 종류만 남긴다(지금 `httpError`와 같음).
- `baseUrl` 검증(설정을 읽을 때): `https://`, 또는 `http://`이면서 호스트가 `localhost`·`127.0.0.1`·`::1`일 때만 허용. 그 밖의 `http://`는 키가 암호화 없이 나가므로 거부하고 오류를 보여 준다.
- 01의 동의: 호스트가 로컬 주소면 동의를 건너뛴다(밖으로 나가지 않음). 그 밖에는 호스트가 바뀔 때마다 다시 동의를 받는다(01의 `consentedHost`).

## 범위에서 빼는 것
- 게임 안 연결 설정 화면(PAIA의 Mods → Connection). toml 파일로만 설정
- Gemini 등 각 제공자의 고유 API. OpenAI 호환 엔드포인트가 있으면 `openai`로 쓸 수 있지만 확인 대상은 아님
- 제공자별 비용 표시

## 완료 기준
- [ ] 1. 단위 테스트(요청 본문): OpenAI 호환의 `pickItem`, `advise`, `converse`(도구 포함·도구 끔) 요청 JSON이 기대 형식과 같다.
- [ ] 2. 단위 테스트(응답 읽기): 일반 답, `tool_calls` 여러 개, `arguments`가 깨진 JSON, `content`가 null인 도구 호출 응답.
- [ ] 3. 단위 테스트: `pickItem` 형식 오류 → 한 번 재요청 → 또 실패하면 `question` (가짜 HTTP 응답으로).
- [ ] 4. 단위 테스트: `baseUrl` 검증 — `https://x`, `http://localhost:11434/v1` 허용, `http://example.com` 거부.
- [ ] 5. 단위 테스트: 상태 코드 401·429·500·400이 각각 기존 `AiException.Kind`로 바뀐다.
- [ ] 6. 기존 설정 파일(`provider` 없음)로 실행하면 지금과 똑같이 Claude를 쓴다.
- [ ] 7. 게임 확인: `provider = ollama`, 로컬 모델 하나로 `/ai 기계식 피스톤 만들고 싶어`와 자유 질문이 동작한다. 동의 안내가 뜨지 않는다.
- [ ] 8. 게임 확인: `provider = offline`에서 네트워크 호출이 없고(로그 확인), `/ai create:mechanical_piston`은 동작하며, 자유 질문은 안내 문구가 나온다.

## 위험
- **R1. Ollama의 구조화 출력·도구 호출:** OpenAI 호환 엔드포인트에서 `response_format: json_schema`와 `tools`를 어디까지 지원하는지, 쓸 모델에서 직접 확인한다. 지원하지 않으면 `pickItem`은 "JSON만 답하라" 프롬프트 + 4절 복구로 대신한다.
- **R2. 로컬 모델 응답 시간:** 요청 시간 제한 90초(`REQUEST_TIMEOUT`)가 로컬 CPU 실행에는 짧을 수 있다. 게임 확인 때 측정하고 필요하면 `requestTimeoutSeconds` 설정을 추가한다.

## 결정 필요 (추천안 먼저)
1. **Ollama 구현:** OpenAI 호환 재사용 추천(구현 하나 줄어듦). 네이티브 API를 쓰면 도구 호출 형식이 또 하나 늘어난다.
2. **오프라인 모드의 후보 제시:** 비슷한 후보 5개 보여 주기 추천. 빼면 "찾지 못함"만 나온다.
