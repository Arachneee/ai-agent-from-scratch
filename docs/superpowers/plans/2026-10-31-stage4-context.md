# 컨텍스트 관리 단계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 이 계획은 학습자와 대화하면서 진행하므로 서브에이전트에게 맡기지 않는다.

**Goal:** 40턴 상담의 마지막 턴 입력 토큰을 학습자가 정한 기준 이하로 유지하고, 그 대가로 잃은 것을 확인 지점의 정답 수로 잰다.

**Architecture:** 측정에 필요한 캐시 토큰 표시, 40턴 시나리오, 실행기는 Claude가 만든다. 기록을 줄이는 부분은 학습자가 Task 4에서 지시하고, 이 계획은 그 모양을 미리 정하지 않는다. Task 4 끝의 "확정된 모양"에 결정을 적고, 뒤의 Task는 그 항목을 읽고 시작한다.

**Tech Stack:** 앞 단계와 같다. Kotlin 2.3.21, JDK 21, Ktor 클라이언트 3.6.1, kotlinx.serialization 1.12.0, kotlinx-coroutines 1.10.2, kotlin-test와 JUnit 5. 새 의존성은 추가하지 않는다.

**Spec:** `docs/superpowers/specs/2026-10-31-stage4-context-design.md`, 그리고 `docs/roadmap.md`의 "컨텍스트 관리" 절.

## Global Constraints

- 기록을 줄이는 부분은 학습자가 지시한 내용만 옮긴다. 입력 토큰을 무엇으로 셀지, 언제 줄일지, 무엇을 남기고 자르거나 요약할지, 툴 메시지 짝을 어떻게 지킬지가 여기에 들어간다. `Agent`, `ChatRequest`, `ChatResponse`, `ChatClient`, `OpenAiClient`의 변경도 학습자가 지시한다.
- 지시에 없는 필드, 분기, 예외 처리를 덧붙이지 않는다. 지시가 모호하거나, 컴파일되지 않거나, 동작이 틀리면 고치지 않고 알린 뒤 제안을 함께 적고 학습자의 결정을 기다린다. Review Focus의 테스트가 학습자의 모양으로 통과하지 않을 때도 같다.
- 개선 전 수치를 재기 전에는 기록을 줄이는 설계를 시작하지 않는다.
- 주문번호를 잊는 문제는 잴 뿐 고치지 않는다. 고칠지는 측정 뒤에 학습자가 정한다.
- 툴은 지금의 `get_order`와 `get_shipment` 두 개뿐이다. 시스템 프롬프트는 바꾸지 않는다.
- 모델은 `ModelPricing.DEFAULT_MODEL`인 `gpt-4o-mini`를 쓴다.
- `framework` 모듈에는 주문, 배송, 환불 같은 쇼핑몰 개념이 들어가지 않는다.
- 자동 테스트는 가짜 `ChatClient`로만 돌고 실제 API를 부르지 않는다.
- API 키는 `OPENAI_API_KEY`로만 읽고 저장소, 기록, 출력 어디에도 남기지 않는다. 응답 헤더는 학습 기록과 테스트용 고정 데이터에 옮기지 않는다.
- 월 OpenAI 비용은 5,000원을 넘지 않는다.
- 사용자에게 보이는 문구와 커밋 메시지는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.

## Review Focus

- 기록을 자르는 경계가 assistant의 툴 호출 메시지와 그 tool 결과 메시지 사이에 떨어지면 서버가 400으로 거부한다. 자른 뒤에도 tool 메시지마다 같은 `tool_call_id`의 툴 호출 메시지가 앞에 있어야 한다. 테스트는 Task 4의 `trimmedHistoryKeepsToolCallPairs`.
- 기록을 줄여도 이번 턴의 사용자 메시지는 남아야 한다. 이 메시지가 잘리면 모델은 무엇에 답해야 할지 모른다. 테스트는 Task 4의 `currentUserMessageIsNeverTrimmed`.
- `cached_tokens`는 `prompt_tokens`에 이미 들어 있는 수다. 비용을 셀 때 캐시된 토큰을 두 번 세면 안 된다. 테스트는 Task 1의 `cachedTokensArePricedSeparatelyNotTwice`.
- 40턴 상담 도중 한 턴이 모델 오류로 실패해도 실행기는 멈추지 않고 그 턴을 오류로 표시한 뒤 다음 발화로 넘어가야 한다. 테스트는 Task 2의 `modelErrorTurnIsMarkedAndRunContinues`.
- 확인 지점의 판정 문자열은 두 주문을 구분해야 한다. 다른 주문의 상태를 답한 것을 정답으로 세면 안 된다. 테스트는 Task 2의 `checkpointRejectsTheOtherOrdersStatus`.

---

### Task 1: 캐시 토큰과 캐시 가격

**Files:**
- Modify: `framework/src/main/kotlin/aiagent/framework/model/ModelPricing.kt`, `example-shop/src/main/kotlin/aiagent/shop/agent/ModelResponder.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Test: `framework/src/test/kotlin/aiagent/framework/model/ModelPricingTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/agent/ModelResponderTest.kt`

**Interfaces:**
- Produces: `ModelPricing.costUsd(model: String, inputTokens: Int, cachedTokens: Int, outputTokens: Int): Double?`. 캐시되지 않은 입력은 입력 가격, 캐시된 입력은 캐시 가격, 출력은 출력 가격으로 센다. 가격은 실행 시점에 OpenAI 가격표의 Standard 등급에서 확인하고 주석의 확인 날짜를 고친다. 기존 세 인자 함수는 호출부를 모두 옮긴 뒤 없앤다.
- Produces: `CallUsage(inputTokens: Int, cachedTokens: Int, outputTokens: Int, costUsd: Double?)`.
- Produces: `[토큰]` 줄은 `[토큰] 입력 %d (캐시 %d) · 출력 %d · 약 %.4f원`이다.

- [ ] **Step 1: 가격을 확인한다.** OpenAI 가격표에서 `gpt-4o-mini`와 `gpt-4.1-mini`의 캐시 입력 가격을 확인한다. 확인한 값과 날짜를 학습 기록의 측정 절에 적는다.
- [ ] **Step 2: 실패하는 테스트를 쓴다.**
  - `ModelPricingTest.cachedTokensArePricedSeparatelyNotTwice`: `gpt-4o-mini`에서 입력 1,000,000, 캐시 400,000, 출력 0이면 비용은 `600,000 × 입력 가격 + 400,000 × 캐시 가격`을 100만으로 나눈 값이다.
  - `ModelPricingTest.unknownModelHasNoPrice`: 가격표에 없는 모델은 null이다.
  - `ModelResponderTest.cachedTokensAreRecorded`: `prompt_tokens_details.cached_tokens`가 128인 응답을 받으면 `lastUsages.single().cachedTokens`가 128이다.
- [ ] **Step 3: 실행해서 실패를 확인한다.** `./gradlew test`가 새 인자를 찾지 못해 실패해야 한다.
- [ ] **Step 4: 구현한다.**
- [ ] **Step 5: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 6: 커밋한다.** 메시지는 `캐시된 입력 토큰을 보여 주고 캐시 가격으로 비용을 센다`.

### Task 2: 40턴 시나리오와 실행기

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/scenario/LongChat.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/LongChatCommand.kt`
- Modify: `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/scenario/LongChatTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ArgsTest.kt`

**Interfaces:**
- Consumes: Task 1의 `CallUsage`.
- Produces: `data class Checkpoint(val turn: Int, val accepted: List<String>)`. `turn`은 1부터 센다. 답에 `accepted` 가운데 하나라도 들어 있으면 정답이다.
- Produces: `object LongChat { val messages: List<String>; val checkpoints: List<Checkpoint> }`.
  - 발화는 41개 이상이다. 처음 세 턴 안에 `ORD-0001`과 `ORD-0002`를 말한다. 시드 42에서 `ORD-0001`은 상품 준비 중이고 출고 대기, `ORD-0002`는 배송 지연이다.
  - 확인 지점은 30턴 이후에 네 개를 둔다. 주문번호를 다시 말하지 않고 "처음에 말한 첫 번째 주문"처럼 묻는다. 두 주문에 두 개씩이다.
  - `accepted`에는 그 주문의 번호와 그 주문에만 해당하는 상태 문구를 넣는다. `ORD-0001`은 "ORD-0001", "준비 중", "출고 대기"이고, `ORD-0002`는 "ORD-0002", "배송 지연", "지연"이다.
  - 마지막 발화는 툴이 필요 없는 일반 문의다.
- Produces: `data class TurnMeasure(val turn: Int, val inputTokens: Int, val cachedTokens: Int, val costUsd: Double?, val error: String?)`와 `data class LongChatReport(val turns: List<TurnMeasure>, val passed: List<Checkpoint>, val failed: List<Pair<Checkpoint, String>>)`. `failed`의 문자열은 그 턴의 답이다.
- Produces: `suspend fun runLongChat(responder: ModelResponder, messages: List<String>, checkpoints: List<Checkpoint>, out: (String) -> Unit): LongChatReport`. 턴마다 `[턴 %d] 입력 %d (캐시 %d) · 약 %.4f원` 한 줄을 찍는다. 답이 `[모델 오류]`로 시작하면 `error`에 답을 담고 다음 발화로 넘어간다. 끝나면 요약을 찍는다. 요약에는 마지막 턴의 입력 토큰, 전체 입력 토큰 합, 전체 비용, 확인 지점의 정답 수, 틀린 확인 지점의 답이 들어간다.
- Produces: CLI 명령 `long-chat [--seed <정수>]`. 기본 시드는 42이고 장애 설정은 `none`이다. API 키가 필요하다. USAGE에 더한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.** 가짜 `ChatClient`로 `ModelResponder`를 만든다.
  - `LongChatTest.turnInputTokensAreSummedPerTurn`: 첫 발화에 툴 호출 응답(입력 150)과 답(입력 180)이, 둘째 발화에 답(입력 200)이 오면 `turns`의 입력 토큰이 `[330, 200]`이다.
  - `LongChatTest.cachedTokensAreSummedPerTurn`: 같은 조건에서 캐시가 0과 128, 그리고 128이면 `[128, 128]`이다.
  - `LongChatTest.modelErrorTurnIsMarkedAndRunContinues`: 둘째 발화의 요청이 429로 실패하면 둘째 턴의 `error`가 `[모델 오류] 429`로 시작하고, 셋째 발화도 처리된다.
  - `LongChatTest.checkpointPassesWhenReplyHasAcceptedText`: 확인 지점 턴의 답이 "출고 대기 중입니다"이고 `accepted`가 `ORD-0001`의 목록이면 `passed`에 들어간다.
  - `LongChatTest.checkpointRejectsTheOtherOrdersStatus`: 같은 확인 지점에 답이 "배송이 지연되고 있습니다"이면 `failed`에 그 답과 함께 들어간다.
  - `LongChatTest.scriptShape`: `LongChat.messages`가 41개 이상이고, 처음 세 개 안에 `ORD-0001`과 `ORD-0002`가 있으며, 확인 지점 턴의 발화에는 `ORD-`가 없고, 확인 지점이 네 개다.
  - `ArgsTest.longChatParsesSeed`: `long-chat --seed 7`이 시드 7로 읽힌다.
- [ ] **Step 2: 실행해서 실패를 확인한다.**
- [ ] **Step 3: 구현한다.** 실행기는 `ModelResponder.lastUsages`를 턴마다 더한다. 발화 문장은 배송, 교환, 환불, 일반 문의가 섞이게 쓴다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `40턴 상담 시나리오와 실행기를 더한다`.

### Task 3: 개선 전 수치

**Files:**
- Create: `docs/learning/stage4-context.md`

- [ ] **Step 1: 학습 기록을 만든다.** 앞 단계와 같은 형식으로 문제, 재현, 측정 절을 쓴다. 측정 절에는 Task 1에서 확인한 가격을 적는다.
- [ ] **Step 2: 예측을 받는다.** 학습자가 마지막 턴의 입력 토큰, 상담 비용, 캐시가 적용되기 시작하는 턴, 확인 지점 정답 수를 먼저 예측한다.
- [ ] **Step 3: 두 번 잰다.** 학습자가 `./gradlew -q --console=plain :example-shop:run --args="long-chat"`을 두 번 실행한다. 두 회차의 턴별 입력 토큰, 캐시 토큰, 비용, 확인 지점 결과를 기록에 옮기고, 학습자의 해석을 받아 옮긴 뒤 검토한다.
- [ ] **Step 4: 성공 기준의 숫자를 받는다.** 학습자가 개선 전 수치를 보고 "마지막 턴 입력 토큰 몇 이하"를 정한다. 학습 기록의 성공 기준 절에 적는다.
- [ ] **Step 5: 커밋한다.** 메시지는 `기록을 줄이기 전 40턴 상담 수치를 기록한다`.

### Task 4: 기록 줄이기

이 Task는 학습자의 지시로 진행한다.

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/agent/Agent.kt`와 새 파일.
- Test: `framework/src/test/kotlin/aiagent/framework/agent/` 아래 학습자가 정한 이름을 딴 테스트.

**Interfaces:**
- Consumes: Task 3의 개선 전 수치와 성공 기준.
- Produces: 학습자가 정한 기록 줄이기의 타입과 함수, 기준값, 설정 위치. Task 4 끝의 "확정된 모양"에 적는다.

- [ ] **Step 1: 설계에 필요한 사실을 보여 준다.** 다음 세 가지를 OpenAI 문서와 지금 코드에서 확인해 보여 준다.
  - tool 메시지는 같은 `tool_call_id`를 가진 assistant 툴 호출 메시지 바로 뒤의 묶음 안에 있어야 한다.
  - 자동 프롬프트 캐싱이 적용되는 최소 길이와, 앞부분이 같아야 한다는 조건.
  - 지금 `Agent.history`에 쌓이는 메시지의 모양과, 개선 전 측정에서 한 턴이 기록에 더하는 메시지 수.
- [ ] **Step 2: 기록 줄이기를 지시대로 옮긴다.** 옮긴 뒤 40턴 상담의 몇 번째 턴부터 기록이 줄어드는지 설명한다.
- [ ] **Step 3: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 4: 테스트를 쓴다.** 확정된 모양에 맞춰 다음 테스트를 쓴다. 이름은 고정이다.
  - `trimmedHistoryKeepsToolCallPairs`: 툴을 쓴 턴이 여러 개 쌓여 기록이 줄어든 뒤의 요청에서, tool 메시지마다 앞쪽에 같은 `tool_call_id`의 툴 호출 메시지가 있다. 기록이 assistant 툴 호출 메시지로 끝나지도, tool 메시지로 시작하지도 않는다.
  - `systemPromptStaysFirst`: 기록이 줄어든 뒤에도 요청의 첫 메시지는 시스템 프롬프트다.
  - `currentUserMessageIsNeverTrimmed`: 기록이 줄어든 뒤에도 이번 턴의 사용자 메시지가 요청에 있다.
  - `historyIsUntouchedBelowThreshold`: 학습자가 정한 기준에 닿기 전에는 요청의 메시지가 지금과 같다.
  - 학습자가 정한 방식이 요약이면 `summaryReplacesTrimmedMessages`를, 잘라내기면 `oldestTurnsAreTrimmedFirst`를 쓴다.
- [ ] **Step 5: 실행한다.** `./gradlew test`가 통과해야 한다. 실패하면 실패 내용과 원인을 학습자에게 보여 주고 고칠 방법은 학습자의 지시를 받는다. 테스트가 틀린 것이면 Claude가 테스트를 고친다.
- [ ] **Step 6: 검토하고 설명한다.** 학습자가 고르지 않은 동작이 숨어 있으면 짚는다. 이 동작은 고치지 않고 다음 단계로 넘길 문제 후보로 적는다.
- [ ] **Step 7: 커밋한다.** 메시지는 학습자가 고른 방식에 맞춰 한국어로 쓴다.

**확정된 모양:** 학습자가 Claude의 제안을 받아들여 다음과 같이 정했다.

- 토큰 양은 직전 응답의 `prompt_tokens`로 잰다. 상담 전체의 누적 합은 줄일 수 없으므로 쓰지 않는다.
- `AgentSettings`에 `historyTokenLimit: Int? = null`과 `keepRecentTurns: Int = 4`를 더한다. 턴을 시작해 사용자 메시지를 기록에 넣은 뒤, 직전 `prompt_tokens`가 `historyTokenLimit`을 넘으면 줄인다.
- 줄일 때는 사용자 메시지를 경계로 턴 단위로 나누고, 최근 `keepRecentTurns`개의 지난 턴과 이번 턴의 사용자 메시지만 남긴다. 그래서 툴 호출 메시지와 결과 메시지의 짝이 깨지지 않는다. 한 번에 크게 줄여 캐시가 깨지는 턴을 줄인다.
- 잘라 낸 기록을 어떻게 남길지는 전략 패턴으로 고른다. `fun interface HistorySummarizer { suspend fun summarize(previous: String?, dropped: List<MessageRequest>): Summary }`, `Summary(text: String?, usage: ChatResponse.Usage?)`. 미리 만든 전략은 요약 없이 버리는 `DropSummarizer`와, 이전 요약과 잘라 낸 기록을 모델에게 보내 새 요약을 받는 `ModelSummarizer(client, model, instruction)`다.
- `Agent` 생성자에 `summarizer: HistorySummarizer = DropSummarizer`를 둔다. 요약은 시스템 프롬프트 바로 뒤에 `지금까지의 대화 요약: …` 시스템 메시지로 보낸다. 요약 요청의 사용량은 그 턴의 `usages` 맨 앞에 넣는다. `reset()`은 요약과 직전 입력 토큰도 비운다.
- 예제의 쇼핑몰 요약 지시문은 기본 지시문에 "고객이 말한 주문번호와 각 주문의 상태는 반드시 포함하라"를 더한다. `long-chat`은 `--summary <none|drop|model>`로 전략을 고르고, 기본은 `model`, 상한은 1,800이다.
- 한 시간 뒤 항상 줄이기는 다음 단계로 넘긴다.

### Task 5: 개선 후 수치와 배치 판단

**Files:**
- Modify: `docs/learning/stage4-context.md`

- [ ] **Step 1: 예측을 받는다.** 학습자가 개선 후 마지막 턴의 입력 토큰, 상담 비용, 캐시 적용, 확인 지점 정답 수를 먼저 예측한다.
- [ ] **Step 2: 두 번 잰다.** Task 3과 같은 명령으로 두 번 잰다. 개선 전후를 나란히 적는다. 성공 판정은 Task 3에서 정한 숫자를 따른다.
- [ ] **Step 3: 해석을 받는다.** 학습자의 해석을 옮기고 검토한다. 확인 지점의 정답 수가 떨어졌다면 주문번호를 잊는 문제를 이번 단계에서 고칠지 학습자에게 묻는다.
- [ ] **Step 4: 배치 판단을 기록한다.** 기록 줄이기의 각 부분을 프레임워크와 예제 가운데 어디에 두었는지와 그 이유를 학습자의 말로 적는다.
- [ ] **Step 5: 커밋한다.** 메시지는 `기록을 줄인 뒤 40턴 상담 수치를 기록한다`.

### Task 6: 마지막 확인

- [ ] **Step 1: 과제를 낸다.** 학습자가 다음 네 가지를 말로 설명한다. 기록을 통째로 보내면 턴마다 입력 토큰이 어떻게 늘어나는지, 고른 방식으로 입력 토큰이 얼마나 왜 줄었는지, 그 방식이 툴 메시지 짝을 어떻게 지키는지, 자동 프롬프트 캐싱이 적용되는 조건과 기록 줄이기가 캐싱에 준 영향.
- [ ] **Step 2: 검토한다.** 학습자의 설명을 학습 기록의 마지막 확인 절에 옮기고, 틀리거나 빠진 부분을 검토로 덧붙인다.
- [ ] **Step 3: 다음 단계로 넘길 문제를 정리한다.** 학습자의 말로 정리한다.
- [ ] **Step 4: 커밋한다.** 메시지는 `컨텍스트 관리 단계 마지막 확인을 기록한다`.
