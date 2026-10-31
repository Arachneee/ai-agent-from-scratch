# 스트리밍 단계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 이 계획은 학습자와 대화하면서 진행하므로 서브에이전트에게 맡기지 않는다.

**Goal:** 학습자가 스트리밍의 네 부분을 직접 설계하고, `slow-shipping` 재현 조건에서 툴 이름과 인자가 실행 직전에, 답의 글자가 도착하는 대로 화면에 나오게 만든다.

**Architecture:** 스트림 읽기, 조각 읽기, 조립, 진행 알림의 틀은 `framework` 모듈에 두고, 모양과 내용은 학습자가 Task 3과 Task 4에서 지시한다. 이 계획은 그 모양을 미리 정하지 않는다. 그래서 해당 Task의 끝에 "확정된 모양" 항목을 채우고, 뒤의 Task는 그 항목을 읽고 시작한다. 재현용 장애 설정, `[시간]` 줄, 채팅 화면 연결, 테스트는 Claude가 설계하는 주변 코드다.

**Tech Stack:** 앞 단계와 같다. Kotlin 2.3.21, JDK 21, Ktor 클라이언트 3.6.1, kotlinx.serialization 1.12.0, kotlinx-coroutines 1.10.2, kotlin-test와 JUnit 5. 새 의존성은 추가하지 않는다.

**Spec:** `docs/superpowers/specs/2026-10-31-stage3-streaming-design.md`, 그리고 `docs/roadmap.md`의 "스트리밍" 절.

## Global Constraints

- 네 부분은 학습자가 지시한 내용만 옮긴다. 네 부분은 서버 전송 이벤트를 줄 단위로 읽어 이벤트로 나누는 부분, 이벤트마다 실린 응답 조각을 읽는 부분, 글자와 툴 호출 인자를 이어 붙여 응답으로 조립하는 부분, 진행 상황을 바깥으로 알리는 통로다. 기존 `ChatRequest`, `ChatResponse`, `ChatClient`, `OpenAiClient`, `Agent`의 변경도 학습자가 지시한다.
- 지시에 없는 필드, 분기, 예외 처리를 덧붙이지 않는다. 지시가 모호하거나, 지시대로 쓰면 컴파일되지 않거나, 동작이 틀리면 고치지 않고 알린 뒤 제안을 함께 적고 학습자의 결정을 기다린다. 아래 Review Focus의 테스트가 학습자의 모양으로 통과하지 않을 때도 같다.
- 스트림 응답의 형식은 Task 2의 원문 관찰 실험 전까지 학습자에게 보여 주지 않는다. Task 2 Step 1에서는 요청 쪽의 `stream`과 `stream_options.include_usage`만 보여 준다.
- 서버 전송 이벤트는 직접 파싱한다. Ktor의 서버 전송 이벤트 플러그인, OpenAI 공식 SDK, Spring AI, LangChain4j를 쓰지 않는다.
- 툴 이름과 인자는 스트림에서 이름 조각이 도착한 시점이 아니라 각 툴의 실행 직전에 화면에 나온다.
- 턴이 끝난 뒤의 기존 `[툴] 이름 인자 결과: ...` 줄은 그대로 둔다.
- 겪어 보지 않은 실패에 대한 대응을 넣지 않는다. 스트림 도중 끊김의 재시도와 복구, 생성 취소, 병렬 툴 실행이 여기에 해당한다.
- 모델은 `ModelPricing.DEFAULT_MODEL`인 `gpt-4o-mini`를 쓴다.
- `framework` 모듈에는 주문, 배송, 환불 같은 쇼핑몰 개념이 들어가지 않는다.
- 자동 테스트는 `MockEngine`이나 가짜 `ChatClient`로만 돌고 실제 API를 부르지 않는다.
- API 키는 `OPENAI_API_KEY`로만 읽고 저장소, 기록, 출력 어디에도 남기지 않는다. 원문에서 `Authorization` 값은 가린다. 응답 헤더는 학습 기록과 테스트용 고정 데이터에 옮기지 않는다.
- 사용자에게 보이는 문구와 커밋 메시지는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.

## Review Focus

- 한국어 글자 하나는 UTF-8로 3바이트다. 네트워크 읽기가 글자 중간에서 끊겨도 글자가 깨지면 안 된다. 테스트는 Task 3의 `multibyteCharacterSplitAcrossReadsIsIntact`.
- `include_usage`를 켜면 스트림의 마지막 사용량 조각은 `choices`가 빈 배열일 수 있다. 이 조각에서 예외가 나지 않고 사용량이 `[토큰]` 줄로 이어져야 한다. 테스트는 Task 3의 `usageChunkWithEmptyChoicesIsRead`.
- `stream`을 켠 요청이 401이나 429로 실패하면 본문은 이벤트가 아닌 보통 JSON 오류다. 이때도 지금처럼 `[모델 오류] 상태 메시지`가 보여야 한다. 테스트는 Task 3의 `errorStatusOnStreamBecomesChatApiException`.
- 글자 조각을 줄바꿈 없이 찍으므로, 스트리밍한 답 뒤의 `[툴]`, `[토큰]`, `[시간]` 줄이 답 끝에 붙어 나오면 안 된다. 답을 스트리밍으로 찍었으면 같은 답을 다시 찍지도 않는다. 테스트는 Task 5의 `streamedAnswerIsNotPrintedTwiceAndEndsWithNewline`.
- 툴 호출 응답에 문장이 함께 오면, 그 문장은 툴 실행 전에 화면에 나오고 대화 기록에도 툴 호출과 함께 남아야 한다. 테스트는 Task 4의 `textBeforeToolCallsIsStreamedAndKeptInHistory`.

---

### Task 1: 재현 조건, 시간 측정, 개선 전 수치

**Files:**
- Modify: `example-shop/src/main/kotlin/aiagent/shop/fault/FaultPresets.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Create: `docs/learning/stage3-streaming.md`
- Test: `example-shop/src/test/kotlin/aiagent/shop/cli/ArgsTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ChatTurnTest.kt`

**Interfaces:**
- Produces: 장애 설정 `slow-shipping`. `SHIPPING`에만 `ServiceFaults(latencyMs = 2000L..2000L)`을 넣는다. USAGE의 `--faults` 목록에 더한다.
- Produces: `suspend fun runChatTurn(responder: Responder, line: String, out: (String) -> Unit, timeSource: TimeSource = TimeSource.Monotonic)`. `ModelResponder`일 때만 턴 끝의 `[토큰]` 줄들 뒤에 `[시간] 첫 출력 %.1f초 · 전체 %.1f초` 한 줄을 찍는다. 첫 출력은 이 턴에서 `out`이 처음 불린 시각이고, 전체는 최종 답을 찍은 시각이다. `[시간]` 줄 자체는 첫 출력이 될 수 없다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**
  - `ArgsTest.slowShippingDelaysOnlyShipping`: `FaultPresets.get("slow-shipping")`의 `SHIPPING` 지연이 `2000L..2000L`이고 다른 서비스에는 설정이 없다.
  - `ChatTurnTest.timingLineReportsFirstOutputAndTotal`: `TestTimeSource`와 가짜 `ChatClient`로 `ModelResponder`를 만든다. 가짜는 첫 요청에서 시간을 1초 진행한 뒤 `content`가 `확인해 볼게요`인 `get_shipment` 호출 응답을 돌려주고, 둘째 요청에서 2초 진행한 뒤 일반 답을 돌려준다. 한 턴을 하면 마지막 줄이 `[시간] 첫 출력 1.0초 · 전체 3.0초`다.
  - `ChatTurnTest`의 기존 두 테스트는 그대로 통과해야 한다. 정규식 응답기에는 `[시간]` 줄이 없다.
- [ ] **Step 2: 실행해서 실패를 확인한다.** `./gradlew :example-shop:test`가 `slow-shipping`과 `timeSource` 인자를 찾지 못해 실패해야 한다.
- [ ] **Step 3: 구현한다.** 재현 질문에 쓸 주문번호 세 개를 고른다. 시드 42에서 `get_shipment`가 오류 없이 결과를 돌려주는 주문이어야 하고, 고른 번호는 학습 기록에 적는다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `배송 지연 재현 설정과 턴 시간 측정을 더한다`.
- [ ] **Step 6: 개선 전 수치를 잰다.** 학습 기록 파일을 앞 단계와 같은 형식으로 만들고 문제와 재현 절을 쓴다. 학습자가 첫 출력까지 시간과 전체 시간을 먼저 예측한다. 학습자가 `./gradlew -q --console=plain :example-shop:run --args="chat --responder model --faults slow-shipping"`로 재현 질문을 세 번 보낸다. 턴마다 `/reset`을 한다. 세 번의 `[시간]`, `[툴]`, `[토큰]` 줄과 학습자의 해석을 기록에 옮기고 검토한다.
- [ ] **Step 7: 커밋한다.** 메시지는 `스트리밍 전 응답 시간을 기록한다`.

### Task 2: 스트리밍 요청과 스트림 원문 관찰

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/model/ChatRequest.kt`
- Create: `framework/src/test/resources/openai/` 아래 스트림 원문 두 개. 이름은 `chat-stream-text.txt`와 `chat-stream-tool-calls.txt`다.
- Modify: `docs/learning/stage3-streaming.md`

**Interfaces:**
- Consumes: Task 1의 재현 질문과 주문번호.
- Produces: 학습자가 정한 요청 필드. Task 2 끝의 "확정된 모양"에 적는다.
- Produces: 실제 스트림 원문 두 개. Task 3의 테스트용 고정 데이터다. 응답 본문만 담고 헤더는 담지 않는다.

- [ ] **Step 1: 요청 형식을 보여 준다.** OpenAI 문서에서 Chat Completions 요청의 `stream`과 `stream_options.include_usage`를 확인해 학습자에게 보여 준다. 응답이 어떤 형식으로 오는지는 보여 주지 않는다. 학습자가 물으면 이 Task의 실험에서 보게 된다고 답한다.
- [ ] **Step 2: 요청 필드를 지시대로 옮긴다.** 기존 비스트리밍 요청의 JSON이 바뀌지 않아야 하므로, 옮긴 뒤 `./gradlew test`가 통과하는지 확인한다.
- [ ] **Step 3: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 4: 원문을 받는 방법을 준비한다.** 지금의 원문 출력은 Ktor `Logging` 플러그인이라 스트림 본문을 다 받은 뒤에 찍을 가능성이 높다. 그래서 원문 관찰은 `curl -sN`으로 한다. 요청 본문 JSON은 스크래치 디렉터리에 두고, 키는 `-H "Authorization: Bearer $OPENAI_API_KEY"`처럼 환경 변수로만 넘긴다. `-i`나 `-v`는 쓰지 않아서 헤더가 출력되지 않게 한다. 실행 명령은 학습자에게 보여 주고 학습자가 실행한다.
- [ ] **Step 5: 첫 번째 원문 실험을 진행한다.** 툴 정의 없이 "안녕하세요."를 스트리밍으로 보낸다. 학습자가 이벤트 한 줄의 형식, 글자 조각이 실리는 위치, 조각의 크기, 스트림 끝 표시, 사용량이 실리는 위치를 먼저 예측한다. 원문을 `chat-stream-text.txt`로 저장한다.
- [ ] **Step 6: 두 번째 원문 실험을 진행한다.** 툴 정의 두 개를 싣고 재현 질문을 스트리밍으로 보낸다. 학습자가 툴 이름과 호출 식별자가 오는 조각, 인자가 나뉘는 조각 수, 여러 툴 호출이 구분되는 방식을 먼저 예측한다. 원문을 `chat-stream-tool-calls.txt`로 저장한다. 원문에 툴 호출이 세 개보다 적으면 그 사실을 기록하고 학습자와 다시 보낼지 정한다.
- [ ] **Step 7: 실험마다 같은 순서를 지킨다.** 학습자의 예측을 먼저 옮기고, 학습자가 실행하고, 결과와 이유를 학습자의 말로 옮긴 뒤 Claude가 검토한다.
- [ ] **Step 8: 커밋한다.** 메시지는 `스트리밍 요청 필드와 스트림 원문 실험을 기록한다`.

**확정된 모양:** `ChatRequest`에 `val stream: Boolean? = null`과 `@SerialName("stream_options") val streamOptions: StreamOptions? = null`을 더한다. `StreamOptions`는 `ChatRequest` 안의 하위 클래스이고 `@SerialName("include_usage") val includeUsage: Boolean` 하나를 기본값 없이 가진다. 두 필드가 null이면 요청 JSON에서 키가 빠진다.

### Task 3: 스트림 읽기, 조각 읽기, 조립

이 Task는 학습자의 지시로 진행한다.

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/model/` 아래 `ChatClient.kt`, `OpenAiClient.kt`, `ChatResponse.kt`와 새 파일.
- Test: `framework/src/test/kotlin/aiagent/framework/model/` 아래 학습자가 정한 이름을 딴 테스트.

**Interfaces:**
- Consumes: Task 2의 확정된 모양과 원문 두 개.
- Produces: 학습자가 정한 스트리밍 호출, 조각 타입, 조립 결과의 타입과 함수. Task 3 끝의 "확정된 모양"에 적는다.
- Produces: 조립 결과는 Task 4의 루프가 쓸 수 있도록 최종 문장, 툴 호출 목록, 사용량, `finish_reason`을 담아야 한다. 학습자의 지시가 이 조건을 만족하지 않으면 알리고 지시를 기다린다.

- [ ] **Step 1: 스트림을 읽어 이벤트로 나누는 부분을 지시대로 옮긴다.** 학습자가 Task 2에서 본 원문을 바탕으로 지시한다. 옮긴 뒤 원문의 줄이 코드의 어디에서 처리되는지 설명한다.
- [ ] **Step 2: 조각을 읽는 부분을 지시대로 옮긴다.** 옮긴 뒤 각 필드가 원문의 어디에서 오는지 설명한다.
- [ ] **Step 3: 조립하는 부분을 지시대로 옮긴다.**
- [ ] **Step 4: 비스트리밍 경로를 남길지 학습자에게 묻는다.** 연속 요청 실험인 `burst`와 기존 `OpenAiClientTest`가 `chat()`을 쓴다는 사실을 함께 알린다.
- [ ] **Step 5: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 6: 테스트를 쓴다.** 확정된 모양에 맞춰 다음 테스트를 쓴다. 이름은 고정이다.
  - `assemblesTextFromStream`: `chat-stream-text.txt`를 `MockEngine`으로 돌려주면 조립 결과의 문장이 원문의 글자 조각을 순서대로 이어 붙인 것과 같고, 툴 호출은 비어 있다.
  - `assemblesToolCallsFromStream`: `chat-stream-tool-calls.txt`를 돌려주면 툴 호출마다 이름, 호출 식별자, 이어 붙인 인자 문자열이 원문의 값과 같다. 인자 문자열은 각각 JSON으로 풀린다.
  - `eventSplitAcrossReadsGivesSameResult`: 같은 원문을 몇 바이트씩 나눠 채널에 쓰면 조립 결과가 한 번에 쓸 때와 같다.
  - `multibyteCharacterSplitAcrossReadsIsIntact`: `"안"`의 UTF-8 3바이트를 두 번에 나눠 쓴 글자 조각 이벤트를 넣으면 조립된 문장에 `"안"`이 그대로 있다.
  - `usageChunkWithEmptyChoicesIsRead`: `choices`가 빈 배열이고 `usage`만 있는 마지막 조각이 있으면 예외 없이 그 `prompt_tokens`와 `completion_tokens`가 조립 결과의 사용량이 된다.
  - `errorStatusOnStreamBecomesChatApiException`: `stream`을 켠 요청에 `error-401.json`과 상태 401이 오면 `ChatApiException`이 나오고 상태와 메시지가 그 파일의 값이다.
  - 글자 조각이 도착할 때마다 바깥에 알리는 방법이 이 Task의 모양에 들어 있으면 `textDeltasAreReportedInOrder`도 쓴다. 원문의 조각 순서대로 알림이 나가야 한다.
- [ ] **Step 7: 실행한다.** `./gradlew :framework:test`가 통과해야 한다. 실패하면 실패 내용과 원인을 학습자에게 보여 주고 고칠 방법은 학습자의 지시를 받는다. 테스트가 틀린 것이면 Claude가 테스트를 고친다.
- [ ] **Step 8: 검토하고 설명한다.** 원문 한 개가 조립 결과가 되기까지의 흐름을 설명한다. 학습자가 고르지 않은 동작이 숨어 있으면 짚는다. 스트림이 끝 표시 없이 끊기면 어떻게 되는지가 그 예다. 이 동작은 고치지 않고 다음 단계로 넘길 문제 후보로 적는다.
- [ ] **Step 9: 커밋한다.** 메시지는 `서버 전송 이벤트 스트림을 읽고 응답으로 조립한다`.

**확정된 모양:** 학습자가 다음과 같이 정했다.

- 스트림 읽기: `OpenAiClient.chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse`. `preparePost(...).execute { }` 안에서 `readUTF8Line()`으로 줄을 읽는다. 빈 줄은 건너뛰고, `data: ` 접두어를 떼고, `[DONE]`에서 멈춘다. 요청에는 `stream = true`와 `StreamOptions(includeUsage = true)`를 `copy`로 채운다. 실패 상태 코드는 `chat()`과 같이 `ChatApiException`을 던진다. `chatStream`은 아직 `ChatClient` 인터페이스에 없다.
- 조각 읽기: 새 클래스 `ChatChunk(id, created, model, choices: List<Choice>, usage: ChatResponse.Usage?, serviceTier, systemFingerprint)`. `Choice(index, delta: Delta, finishReason: String?)`, `Delta(role?, content?, toolCalls: List<ToolCallDelta>?)`, `ToolCallDelta(index, id?, type?, function: FunctionDelta(name?, arguments))`. 조각에 따라 빠지는 필드만 `= null` 기본값을 둔다. `ChatResponse`는 바꾸지 않는다.
- 조립: 별도 클래스 `ChatStreamAssembler`의 `add(chunk)`와 `result(): ChatResponse`. 글자는 이어 붙이고, 툴 호출은 `index`별로 묶어 `index` 순서로 담는다. `object`는 `chat.completion`, `annotations`는 빈 목록으로 채운다. 사용량, `finish_reason`, `role`, 툴 호출의 `id`, `type`, 이름을 받지 못했으면 `IllegalStateException`을 던진다. 글자 조각이 없으면 `content`는 null이다.
- 비스트리밍 경로 `chat()`은 그대로 남긴다.

### Task 4: 진행 알림 통로와 루프의 스트리밍 전환

이 Task는 학습자의 지시로 진행한다.

**Files:**
- Modify: 학습자의 지시에 따라 `framework/src/main/kotlin/aiagent/framework/agent/Agent.kt`와 새 파일.
- Test: `framework/src/test/kotlin/aiagent/framework/agent/AgentTest.kt`

**Interfaces:**
- Consumes: Task 3의 확정된 모양.
- Produces: 학습자가 정한 진행 알림의 모양. 최소한 글자 조각과 툴 실행 직전의 이름과 인자를 구분해서 알릴 수 있어야 한다. Task 4 끝의 "확정된 모양"에 적는다. Task 5는 이 항목을 읽고 시작한다.
- Produces: `TurnResult`의 `answer`, `usages`, `toolCalls`는 지금과 같은 뜻을 유지한다. 바꿔야 하면 학습자가 지시한다.

- [ ] **Step 1: 진행 알림 통로를 지시대로 옮긴다.** 지금의 `onProgress: (String) -> Unit`을 어떻게 바꿀지 학습자가 정한다.
- [ ] **Step 2: 루프가 스트리밍 호출을 쓰도록 지시대로 바꾼다.**
- [ ] **Step 3: 확정된 모양을 이 계획에 적는다.**
- [ ] **Step 4: 테스트를 쓴다.** 가짜 `ChatClient`를 Task 3의 모양에 맞춰 고친다. 다음 테스트의 이름은 고정이다.
  - `toolProgressIsReportedBeforeEachExecution`: 툴 호출 두 개가 담긴 응답에서, 진행 알림과 툴 실행 함수의 호출 순서가 첫 툴의 알림, 첫 툴의 실행, 둘째 툴의 알림, 둘째 툴의 실행이다. 알림에는 툴 이름과 조립된 인자가 실린다.
  - `textBeforeToolCallsIsStreamedAndKeptInHistory`: 글자 조각과 툴 호출이 함께 온 응답에서, 글자 조각 알림이 툴 실행 전에 나가고, 다음 요청의 assistant 메시지에 그 문장과 툴 호출이 함께 들어 있다.
  - 상한에 걸려 실행하지 않는 툴 호출에는 실행 직전 알림을 보내는지 학습자에게 묻고, 답에 맞춰 `blockedCallProgress`를 쓴다.
  - 기존 `AgentTest`의 테스트는 모두 같은 내용을 확인하도록 가짜 `ChatClient`만 바꿔 유지한다. 기존 `progressIsReportedBeforeToolExecution`은 새 모양으로 바꾼다.
- [ ] **Step 5: 실행한다.** `./gradlew :framework:test`가 통과해야 한다. 실패하면 Task 3 Step 7과 같이 처리한다.
- [ ] **Step 6: 검토하고 설명한다.** 질문 하나가 진행 알림과 최종 답이 되기까지의 흐름을 설명하고, 숨은 동작을 짚는다. 진행 알림을 받는 쪽이 예외를 던지면 대화 기록이 어떻게 되는지가 그 예다.
- [ ] **Step 7: 커밋한다.** 메시지는 `루프가 스트리밍으로 진행 상황을 알린다`.

**확정된 모양:** 학습자가 다음과 같이 정했다.

- 진행 알림: 하나의 콜백에 이벤트 타입으로 넘긴다. `sealed interface AgentEvent`에 `TextDelta(text: String)`와 `ToolCallStarted(name: String, arguments: String)`가 있다. `Agent.run(message: String, onEvent: (AgentEvent) -> Unit = {}): TurnResult`.
- 빈 글자 조각은 알리지 않는다. 툴 호출 응답에 함께 온 문장은 `TextDelta`로 이미 나가므로 조립 뒤에 다시 알리지 않는다.
- `ToolCallStarted`는 상한 안에서 실제로 실행하는 툴에만, `execute` 직전에 보낸다. 상한에 걸린 호출에는 보내지 않는다.
- `ChatClient`에 `suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse`를 더한다. `chat()`은 `Result`, `chatStream`은 예외를 던지는 차이를 그대로 둔다. `Agent`는 `chatStream`만 쓴다.
- `ModelResponder.respond(message, onEvent: (AgentEvent) -> Unit)`는 이벤트를 그대로 넘긴다.

### Task 5: 채팅 화면 연결

**Files:**
- Modify: `example-shop/src/main/kotlin/aiagent/shop/agent/ModelResponder.kt`, `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`, Task 3에서 비스트리밍 경로를 없앴다면 `example-shop/src/main/kotlin/aiagent/shop/cli/Burst.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/agent/ModelResponderTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ChatTurnTest.kt`

**Interfaces:**
- Consumes: Task 4의 확정된 모양, Task 1의 `runChatTurn`.
- Produces: `runChatTurn`에 줄바꿈 없이 찍는 출력 함수를 더한다. 이름은 `write: (String) -> Unit`이고, `Main.kt`에서는 `print`한 뒤 `System.out.flush()`를 부른다. 글자 조각은 `write`로, 나머지 줄은 `out`으로 찍는다.
- Produces: 툴 실행 직전 진행 표시는 `[툴 호출] 이름 인자` 한 줄이다. 글자 조각을 찍는 중에 이 줄이 오면 먼저 줄을 바꾼다.
- Produces: 첫 출력 시각은 `out`과 `write` 가운데 먼저 불린 쪽으로 잰다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**
  - `ChatTurnTest.toolProgressLineIsShownBeforeExecution`: 툴 호출 하나와 답을 차례로 돌려주는 가짜 `ChatClient`로 한 턴을 하면, 출력 순서가 `[툴 호출] get_shipment {"order_id":"ORD-0001"}`, 답 글자, `[툴] ...` 줄이다.
  - `ChatTurnTest.streamedAnswerIsNotPrintedTwiceAndEndsWithNewline`: 답이 `"배송 "`과 `"준비 중입니다."` 두 조각으로 오면 `write`로 두 조각이 순서대로 나가고, 답 전체가 `out`으로 다시 나가지 않으며, 다음 `out` 줄 전에 줄바꿈이 한 번 나간다.
  - `ChatTurnTest.timingLineReportsFirstOutputAndTotal`을 새 모양에 맞춰 고친다. 첫 출력은 첫 `write`나 첫 `out` 시각이다.
  - `ModelResponderTest`의 기존 테스트는 같은 내용을 확인하도록 가짜 `ChatClient`만 바꿔 유지한다.
- [ ] **Step 2: 실행해서 실패를 확인한다.**
- [ ] **Step 3: 구현한다.** `[모델 오류]`와 `[오류]` 줄도 글자 조각 뒤에 오면 먼저 줄을 바꾼다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `채팅 화면에 툴 호출과 답 글자를 바로 보여 준다`.

### Task 6: 개선 후 수치와 나머지 실험

**Files:**
- Modify: `docs/learning/stage3-streaming.md`

- [ ] **Step 1: 개선 후 수치를 잰다.** Task 1 Step 6과 같은 조건과 방법으로 세 번 잰다. 학습자가 먼저 예측한다. 개선 전후의 첫 출력까지 시간과 전체 시간을 나란히 적는다. 성공 판정은 설계 문서의 성공 기준 절을 따른다.
- [ ] **Step 2: 툴 호출과 함께 오는 문장 실험을 진행한다.** `--faults flaky`로 툴 오류 뒤 모델의 재시도 문장이 스트림에서 어떻게 보이는지 본다. 앞 단계처럼 재시도가 늘 일어나지는 않으므로 몇 번 시도했는지도 적는다.
- [ ] **Step 3: 실험마다 같은 순서를 지킨다.** 학습자의 예측을 먼저 옮기고, 학습자가 실행하고, 결과와 이유를 학습자의 말로 옮긴 뒤 Claude가 검토한다.
- [ ] **Step 4: 배치 판단을 기록한다.** 네 부분 각각을 프레임워크와 예제 가운데 어디에 두었는지와 그 이유를 학습자의 말로 적는다.
- [ ] **Step 5: 커밋한다.** 메시지는 `스트리밍 후 응답 시간과 실험 결과를 기록한다`.

### Task 7: 마지막 확인

- [ ] **Step 1: 과제를 낸다.** 학습자가 다음 네 가지를 말로 설명한다. 스트림 이벤트 한 줄의 형식, 스트림이 끝나는 표시, 툴 호출 인자가 조각으로 오는 방식과 이어 붙이는 기준, 첫 출력은 빨라지는데 전체 시간은 그대로인 이유.
- [ ] **Step 2: 검토한다.** 학습자의 설명을 학습 기록의 마지막 확인 절에 옮기고, 틀리거나 빠진 부분을 검토로 덧붙인다.
- [ ] **Step 3: 다음 단계로 넘길 문제를 정리한다.** 이번 단계에서 겪은 실패와 불편, Task 3과 Task 4의 검토에서 짚은 숨은 동작, 측정에서 드러난 툴 실행 시간의 누적을 학습 기록 끝에 학습자의 말로 정리한다.
- [ ] **Step 4: 커밋한다.** 메시지는 `스트리밍 단계 마지막 확인을 기록한다`.
