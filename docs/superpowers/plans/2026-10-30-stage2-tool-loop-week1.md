# 툴 호출 루프 단계 전반부 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 이 계획은 학습자와 대화하면서 진행하므로 서브에이전트에게 맡기지 않는다.

**Goal:** 학습자가 툴 호출 루프의 네 부분을 직접 설계하고, 시드 42에서 "주문번호 ORD-0001 지금 어디쯤 왔어요?"에 모델이 툴로 얻은 실제 값으로 답하게 만든다.

**Architecture:** 툴 정의, 툴 호출 응답 읽기, 툴 실행, 루프, 대화 기록의 틀은 `framework` 모듈의 `aiagent.framework` 아래에 두고, 모양과 내용은 학습자가 Task 1과 Task 3에서 지시한다. 이 계획은 그 모양을 미리 정하지 않는다. 그래서 각 Task의 끝에 "확정된 모양" 항목을 채우고, 뒤의 Task는 그 항목을 읽고 시작한다. 주문 조회 툴과 배송 조회 툴의 구체적인 정의와 실행, 채팅 연결, 테스트는 Claude가 설계하는 주변 코드다.

**Tech Stack:** 모델 호출 단계와 같다. Kotlin 2.3.21, JDK 21, Ktor 클라이언트 3.6.1, kotlinx.serialization 1.12.0, kotlinx-coroutines 1.10.2, kotlin-test와 JUnit 5. 새 의존성은 추가하지 않는다.

**Spec:** `docs/superpowers/specs/2026-10-30-stage2-tool-loop-week1-design.md`, 그리고 `docs/roadmap.md`의 "툴 호출 루프" 절.

## Global Constraints

- 네 부분은 학습자가 지시한 내용만 옮긴다. 네 부분은 툴 정의를 요청에 싣는 부분, 모델의 툴 호출 응답을 읽는 부분, 호출할 툴을 골라 실행하는 부분, 툴 결과를 돌려보내며 반복하는 루프다. 대화 기록의 모양, 툴 결과를 모델에게 돌려주는 형식, 기존 `ChatRequest`, `ChatResponse`, `OpenAiClient`의 변경도 학습자가 지시한다.
- 지시에 없는 필드, 분기, 예외 처리를 덧붙이지 않는다. 지시가 모호하거나, 지시대로 쓰면 컴파일되지 않거나, 동작이 틀리면 고치지 않고 알린 뒤 다음 지시를 기다린다. 이때 제안을 함께 적을 수 있다.
- 툴 호출 응답의 형식은 Task 2의 세 번째 실험 전까지 학습자에게 보여 주지 않는다. Task 1에서는 OpenAI 문서의 툴 정의 요청 형식만 보여 준다.
- 겪어 보지 않은 실패에 대한 대응을 넣지 않는다. 최대 반복 수, 반복 감지, 툴 오류를 결과로 바꾸는 처리, 병렬 실행, 스키마 자동 생성이 여기에 해당한다.
- 툴은 조회만 한다. 주문 조회 툴은 `OrderService.getOrder`를, 배송 조회 툴은 `ShippingService.getShipment`를 부른다. 스키마는 손으로 쓴다.
- 모델은 `ModelPricing.DEFAULT_MODEL`인 `gpt-4o-mini`를 쓴다.
- `framework` 모듈에는 주문, 배송, 환불 같은 쇼핑몰 개념이 들어가지 않는다.
- 자동 테스트는 `MockEngine`이나 가짜 `ChatClient`로만 돌고 실제 API를 부르지 않는다.
- 사용자에게 보이는 문구, 시스템 프롬프트, 커밋 메시지는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.
- 시스템 프롬프트를 고치면 그 내용을 학습자에게 보여 주고, 실험 결과에 영향을 주는 변경이라는 점을 학습 기록에 남긴다.

## Review Focus

- 툴 정의를 끈 요청에는 `tools` 키가 아예 없어야 한다. 빈 배열을 보내면 서버가 거부한다. 테스트는 Task 1의 `toolsOffOmitsToolsKey`.
- 툴 호출의 인자는 JSON 객체가 아니라 JSON을 담은 문자열로 온다. 한국어나 따옴표가 든 인자도 올바르게 풀려야 한다. 테스트는 Task 3의 `parsesToolCallArgumentsString`.
- 툴을 쓴 턴 다음 턴의 요청에는 assistant의 툴 호출 메시지와 tool 결과 메시지가 그 순서대로 남아 있어야 한다. 순서가 어긋나면 서버가 400으로 거부한다. 테스트는 Task 3의 `nextTurnKeepsToolMessagesInOrder`.
- 루프 도중에 예외가 나도 채팅은 멈추지 않고 오류 한 줄을 보여 준 뒤 다음 입력을 받아야 한다. 테스트는 Task 4의 `chatTurnSurvivesUnexpectedException`.
- 한 턴에 요청이 여러 번 나가면 요청마다 토큰 줄이 하나씩 보여야 한다. 테스트는 Task 4의 `usagePerRequestIsReported`.

---

### Task 1: 툴 정의를 요청에 싣기

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/model/ChatRequest.kt`와 새 파일.
- Create: `example-shop/src/main/kotlin/aiagent/shop/agent/ShopTools.kt`
- Modify: `example-shop/src/main/kotlin/aiagent/shop/agent/ModelSettings.kt`, `ModelResponder.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/ChatCommands.kt`
- Test: `framework/src/test/kotlin/aiagent/framework/model/` 아래 학습자가 정한 이름을 딴 테스트, `example-shop/src/test/kotlin/aiagent/shop/agent/ModelResponderTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ChatCommandsTest.kt`

**Interfaces:**
- Produces: 학습자가 정한 툴 정의 타입과, 요청에 그 정의를 싣는 방법. Task 1 끝의 "확정된 모양"에 적는다.
- Produces: `ShopTools`에 주문 조회 툴과 배송 조회 툴의 정의 두 개. 이름은 `get_order`와 `get_shipment`이고, 두 툴 모두 문자열 인자 `order_id` 하나를 필수로 받는다. 설명 문구는 한국어 한 문장이다.
- Produces: `ModelSettings`에 `toolsEnabled: Boolean`을 더한다. 기본값은 `true`다. 채팅 명령은 `/tools <on|off>`다.

- [ ] **Step 1: 요청 형식을 보여 준다.** OpenAI 문서에서 Chat Completions 요청의 `tools` 필드 형식을 확인해 학습자에게 보여 준다. `type`, `function.name`, `function.description`, `function.parameters`가 대상이다. 툴 호출 응답의 형식과 `tool_choice`, `parallel_tool_calls`는 보여 주지 않는다. 학습자가 물으면 세 번째 실험에서 보게 된다고 답한다.
- [ ] **Step 2: 툴 정의 타입과 요청 필드를 지시대로 옮긴다.** 옮긴 뒤 각 필드가 JSON의 어디가 되는지 설명한다.
- [ ] **Step 3: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 4: 실패하는 테스트를 쓴다.** 확정된 모양에 맞춰 다음 테스트를 쓴다.
  - framework `toolDefinitionSerializesToOpenAiShape`: 이름 `lookup`, 설명 `설명`, 문자열 인자 `id` 하나를 가진 툴 정의를 실은 요청을 직렬화하면 `tools[0].type`이 `function`이고 `tools[0].function.name`이 `lookup`이며 `tools[0].function.parameters.properties.id.type`이 `string`, `required`가 `["id"]`다.
  - `ModelResponderTest.toolsAreSentWhenEnabled`: `toolsEnabled = true`이면 가짜 `ChatClient`가 받은 요청에 `get_order`와 `get_shipment` 두 정의가 있다.
  - `ModelResponderTest.toolsOffOmitsToolsKey`: `toolsEnabled = false`이면 받은 요청을 직렬화한 JSON에 `tools` 키가 없다.
  - `ChatCommandsTest`: `/tools off`와 `/tools on`이 `toolsEnabled`만 바꾸고, `/tools maybe`는 설정을 그대로 두고 사용법을 보여 준다. `/settings` 문구에 `tools=on`이 들어간다.
- [ ] **Step 5: 실행해서 실패를 확인한다.** `./gradlew test`가 새 정의를 찾지 못해 실패해야 한다.
- [ ] **Step 6: 구현한다.** `ShopTools`의 두 정의와 `/tools` 명령을 만들고, `ModelResponder`가 `toolsEnabled`일 때만 요청에 정의를 싣게 한다. 이 Task에서는 툴 호출 응답을 읽지 않는다. 모델이 툴을 부르면 지금처럼 `content`가 비어 `[빈 응답] finish_reason=...`이 보인다.
- [ ] **Step 7: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 하고 기존 테스트도 모두 통과해야 한다.
- [ ] **Step 8: 커밋한다.** 메시지는 `툴 정의를 요청에 싣는다`.

**확정된 모양:** Step 3에서 채운다.

### Task 2: 툴 호출 응답을 처음 보는 실험

이 Task는 학습자와 함께하는 실험이다.

**Files:**
- Create: `docs/learning/stage2-tool-loop.md`
- Create: `framework/src/test/resources/openai/chat-completion-tool-calls.json`

- [ ] **Step 1: 학습 기록 틀을 만든다.** 설계 문서의 실험 여섯 개를 소제목으로 두고, 각각 예측, 실제 결과, 달랐던 이유 자리를 둔다. 끝에 마지막 확인과 후반부로 넘길 문제 절을 둔다.
- [ ] **Step 2: 첫 실험을 진행한다.** 툴 정의가 입력 토큰을 얼마나 늘리는지 본다. `/reset` 뒤 온도 0에서 같은 질문을 `/tools off`와 `/tools on`으로 한 번씩 보낸다. 질문은 툴을 부르지 않을 만한 "안녕하세요."로 한다. 학습자가 예측을 말하면 옮기고, 결과와 이유도 학습자의 말로 옮긴다. Claude는 `o200k_base`로 툴 정의 JSON의 토큰 수를 세어 비교를 돕는다.
- [ ] **Step 3: 두 번째 실험을 진행한다.** 툴을 켠 채 "안녕하세요."를 보내 모델이 툴을 부르는지 본다. 첫 실험의 결과로 대신할 수 있으면 학습자의 동의를 받고 묶는다.
- [ ] **Step 4: 세 번째 실험을 진행한다.** 원문 출력을 켜고 "주문번호 ORD-0001 지금 어디쯤 왔어요?"를 보낸다. 학습자는 실행 전에 `finish_reason`, `content`, 툴 호출 정보의 위치와 인자 형식을 예측한다. 실행 뒤 응답 본문 원문을 받아서 `chat-completion-tool-calls.json`으로 저장한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `툴 호출 응답 첫 실험을 기록한다`.

### Task 3: 툴 호출 응답 읽기, 툴 실행, 루프, 대화 기록

이 Task는 학습자의 지시로 진행한다.

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/model/ChatResponse.kt`, `ChatRequest.kt`와 새 파일.
- Test: `framework/src/test/kotlin/aiagent/framework/` 아래 학습자가 정한 이름을 딴 테스트.

**Interfaces:**
- Consumes: Task 1의 확정된 모양, Task 2의 `chat-completion-tool-calls.json`.
- Produces: 학습자가 정한 응답 읽기, 툴 실행, 루프, 대화 기록의 타입과 함수. Task 3 끝의 "확정된 모양"에 적는다. Task 4는 이 항목을 읽고 시작한다.
- Produces: 루프는 `ChatClient`를 바깥에서 받아야 하고, 툴 실행은 예제 모듈이 툴마다 실행 함수를 넘길 수 있어야 한다. 학습자의 지시가 이 조건을 만족하지 않으면 알리고 지시를 기다린다.

- [ ] **Step 1: 응답 읽는 부분을 지시대로 옮긴다.** 학습자가 Task 2에서 본 실제 응답을 바탕으로 지시한다. 옮긴 뒤 각 필드가 원문의 어디에서 오는지 설명한다.
- [ ] **Step 2: 툴을 골라 실행하는 부분을 지시대로 옮긴다.** 툴 결과를 모델에게 돌려주는 형식도 학습자가 정한다.
- [ ] **Step 3: 루프와 대화 기록을 지시대로 옮긴다.** 대화 기록은 예제 모듈의 `ModelResponder`에서 프레임워크로 옮기는 것이 목표다. 기존 `ModelResponder`의 대화 기록 처리를 어떻게 바꿀지도 학습자가 정한다.
- [ ] **Step 4: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 5: 테스트를 쓴다.** 확정된 모양에 맞춰 다음 테스트를 쓴다. 이름은 고정이다.
  - `parsesToolCallResponse`: `chat-completion-tool-calls.json`을 넣으면 툴 이름과 인자, 툴 호출 ID를 그 파일의 값대로 꺼낸다.
  - `parsesToolCallArgumentsString`: 인자 문자열이 `{"order_id":"주문 \"ORD-0001\""}`인 툴 호출에서 `order_id` 값이 `주문 "ORD-0001"`로 풀린다.
  - `loopSendsToolResultThenStops`: 가짜 `ChatClient`가 첫 요청에 툴 호출 응답을, 둘째 요청에 일반 응답을 돌려주면 요청은 정확히 두 번 나간다. 둘째 요청의 `messages`에는 assistant의 툴 호출 메시지와, 같은 툴 호출 ID를 가진 tool 결과 메시지가 그 순서대로 들어 있다. 루프는 둘째 응답의 내용을 최종 답으로 돌려준다.
  - `nextTurnKeepsToolMessagesInOrder`: 위 턴 뒤에 새 사용자 메시지로 한 턴을 더 하면, 셋째 요청의 `messages` 역할 순서가 `system`, `user`, `assistant`, `tool`, `assistant`, `user`다.
  - `executesEachToolCallInOrder`: 한 응답에 툴 호출이 두 개 있으면 두 실행 함수가 응답에 나온 순서대로 한 번씩 불리고, 다음 요청에 tool 결과 메시지가 두 개 들어간다.
- [ ] **Step 6: 실행한다.** `./gradlew :framework:test`가 통과해야 한다. 실패하면 실패 내용과 원인을 학습자에게 보여 주고 고칠 방법은 학습자의 지시를 받는다. 테스트가 틀린 것이면 Claude가 테스트를 고친다.
- [ ] **Step 7: 검토하고 설명한다.** 완성된 코드를 처음부터 읽으며 질문 하나가 최종 답이 되기까지의 흐름을 설명한다. 학습자가 고르지 않은 동작이 숨어 있으면 짚는다. 툴 실행 중 예외가 나면 어떻게 되는지, 모델이 툴을 끝없이 부르면 어떻게 되는지가 그 예다. 이 동작은 고치지 않고 후반부 후보로 적는다.
- [ ] **Step 8: 커밋한다.** 메시지는 `툴 호출 루프와 대화 기록을 프레임워크에 추가한다`.

**확정된 모양:** Step 4에서 채운다.

### Task 4: 쇼핑몰 툴 실행과 채팅 연결

**Files:**
- Modify: `example-shop/src/main/kotlin/aiagent/shop/agent/ShopTools.kt`, `ModelResponder.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/agent/ShopToolsTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/agent/ModelResponderTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ChatTurnTest.kt`

**Interfaces:**
- Consumes: Task 3의 확정된 모양, `ShopWorld`.
- Produces: `ShopTools(world: ShopWorld)`가 두 툴의 정의와 실행 함수를 Task 3의 툴 실행 형식으로 내놓는다. 실행 결과는 Task 3에서 학습자가 정한 툴 결과 형식을 따른다.
- Produces: `ModelResponder`는 Task 3의 루프와 대화 기록을 쓴다. `lastUsage: CallUsage?` 대신 `lastUsages: List<CallUsage>`로 한 턴의 요청마다 사용량을 하나씩 담는다.
- Produces: `suspend fun runChatTurn(responder: Responder, line: String, out: (String) -> Unit)`를 `Main.kt`의 채팅 루프에서 떼어 내 한 턴의 처리와 출력을 맡긴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**
  - `ShopToolsTest.getOrderReturnsSeed42Status`: `ShopWorld.create(42)`로 `get_order`를 `order_id` `ORD-0001`로 실행하면 결과에 주문 상태 `PREPARING`이 들어 있다.
  - `ShopToolsTest.getShipmentReturnsSeed42Status`: 같은 조건으로 `get_shipment`를 실행하면 결과에 배송 상태 `READY`가 들어 있다.
  - `ModelResponderTest.usagePerRequestIsReported`: 툴 호출 응답과 일반 응답을 차례로 돌려주는 가짜 `ChatClient`로 한 턴을 하면 `lastUsages`의 크기가 2이고 각 원소의 입력 토큰이 두 응답의 `prompt_tokens`와 같다.
  - `ModelResponderTest.answersFromToolResult`: 위 조건에서 `respond`의 결과가 둘째 응답의 `content`다.
  - `ChatTurnTest.chatTurnSurvivesUnexpectedException`: `respond`가 `IllegalStateException("깨짐")`을 던지는 응답기로 `runChatTurn`을 부르면 예외가 밖으로 나오지 않고 `out`에 `[오류] IllegalStateException: 깨짐` 한 줄이 나온다.
  - 기존 `ModelResponderTest`의 대화 기록 테스트는 Task 3의 대화 기록으로 옮긴 뒤에도 같은 내용을 확인하도록 고친다.
- [ ] **Step 2: 실행해서 실패를 확인한다.**
- [ ] **Step 3: 구현한다.** `ShopTools`의 실행 함수는 `world.orders.getOrder`와 `world.shipping.getShipment`를 부른다. `modelChat`은 `ShopWorld`를 받아 `ShopTools`를 만든다. `runChatTurn`은 `ServiceException`은 지금처럼 `[서비스 오류]`로, 그 밖의 예외는 `[오류] <클래스 이름>: <메시지>`로 보여 준다. 응답 뒤에는 `lastUsages`의 원소마다 `[토큰]` 줄을 하나씩 보여 준다. 시스템 프롬프트는 고치지 않는다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `주문 조회와 배송 조회 툴을 채팅에 연결한다`.

### Task 5: 나머지 실험과 학습 기록

**Files:**
- Modify: `docs/learning/stage2-tool-loop.md`

- [ ] **Step 1: 네 번째 실험을 진행한다.** "주문번호 ORD-0001 지금 어디쯤 왔어요?"에서 최종 답까지 요청이 몇 번 오가는지, 툴을 하나씩 부르는지 한 번에 여러 개 부르는지 본다.
- [ ] **Step 2: 다섯 번째 실험을 진행한다.** 모델 호출 단계의 여덟 번째 실험과 같은 조건으로 보내고, 답이 시드 42의 실제 상태와 맞는지 본다. 이 결과가 이번 주의 성공 판정이다. 판정 기준은 설계 문서의 성공 기준 절을 따른다.
- [ ] **Step 3: 여섯 번째 실험을 진행한다.** 주문번호 없이 "제 주문 어디쯤 왔어요?"를 보내, 모델이 툴을 부르는지, 주문번호를 묻는지, 인자를 지어내서 부르는지 본다.
- [ ] **Step 4: 실험마다 같은 순서를 지킨다.** 학습자의 예측을 먼저 옮기고, 학습자가 실행하고, 결과와 이유를 학습자의 말로 옮긴 뒤 Claude가 검토한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `툴 호출 루프 실험 기록을 추가한다`.

### Task 6: 마지막 확인

- [ ] **Step 1: 과제를 낸다.** 다음 Spring AI 코드를 보여 주고, 툴 정의가 실리는 위치, 툴 호출 응답의 형식, 툴을 실행하는 주체, 결과가 돌아가는 형식, 루프가 끝나는 조건을 학습자가 말로 설명하게 한다.

```kotlin
class OrderTools(private val orders: OrderService) {
    @Tool(description = "주문번호로 주문 상태를 조회한다")
    fun getOrder(@ToolParam(description = "주문번호") orderId: String): String =
        runBlocking { orders.getOrder(orderId).status.name }
}

val answer = chatClient.prompt()
    .user("주문번호 ORD-0001 지금 어디쯤 왔어요?")
    .tools(OrderTools(orderService))
    .call()
    .content()
```

- [ ] **Step 2: 검토한다.** 학습자의 설명을 학습 기록의 마지막 확인 절에 옮기고, 틀리거나 빠진 부분을 검토로 덧붙인다.
- [ ] **Step 3: 후반부의 문제를 정리한다.** 이번 주에 겪은 실패와 불편, Task 3 Step 7에서 짚은 숨은 동작을 학습 기록 끝에 학습자의 말로 정리한다.
- [ ] **Step 4: 커밋한다.** 메시지는 `툴 호출 루프 전반부 마지막 확인을 기록한다`.
