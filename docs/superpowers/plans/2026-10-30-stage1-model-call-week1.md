# 모델 호출 단계 전반부 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 이 계획은 학습자와 대화하면서 진행하므로 서브에이전트에게 맡기지 않는다.

**Goal:** 학습자가 Chat Completions 호출의 핵심 코드를 직접 설계하고, 실제 모델과 대화하는 실험을 예측과 실행 순서로 진행해서, Spring AI 호출 한 줄 아래의 HTTP 요청과 응답을 설명할 수 있게 한다.

**Architecture:** 호출 클라이언트와 요청, 응답 타입은 `framework` 모듈의 `aiagent.framework.model` 패키지에 두고, 이 코드의 모양과 내용은 Task 2에서 학습자가 지시한다. 이 계획은 핵심 코드의 이름과 시그니처를 미리 정하지 않는다. 그래서 Task 3부터는 Task 2에서 정해진 모양을 그대로 쓰며, 각 Task를 시작할 때 Task 2의 결과를 다시 읽는다. 원문 출력, 비용 계산, 모델 응답기, 채팅 명령, 몰아 보내기 명령은 Claude가 설계하는 주변 코드다.

**Tech Stack:** Kotlin 2.3.21, JDK 21, Ktor 클라이언트 3.6.1(`ktor-client-core`, `ktor-client-cio`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, 테스트용 `ktor-client-mock`), kotlinx.serialization 1.12.0과 Kotlin 직렬화 플러그인, kotlinx-coroutines 1.10.2, kotlin-test와 JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-30-stage1-model-call-week1-design.md`, 그리고 `docs/roadmap.md`의 "모델 호출" 절과 "비용 상한 운용" 절.

## Global Constraints

- 공식 OpenAI SDK, Spring AI, LangChain4j를 쓰지 않는다. HTTP는 Ktor 클라이언트, 직렬화는 kotlinx.serialization을 쓴다. `ContentNegotiation` 플러그인은 써도 된다.
- API 키는 `OPENAI_API_KEY` 환경 변수에서만 읽는다. 키는 저장소, 로그, 화면 출력 어디에도 남기지 않는다. 요청 원문을 보여 줄 때 `Authorization` 헤더 값은 가린다.
- OpenAI 비용은 한 달에 5,000원 이하로 유지한다. 이번 주 실험은 수십 번 안팎의 호출만 한다.
- `framework` 모듈에는 주문, 환불, 배송 같은 쇼핑몰 개념이 들어가지 않는다.
- 채팅 화면의 문구, 시스템 프롬프트, 커밋 메시지는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.
- 핵심 코드는 학습자가 지시한 내용만 옮긴다. 지시에 없는 필드, 분기, 예외 처리를 덧붙이지 않는다. 지시가 모호하거나, 지시대로 쓰면 컴파일되지 않거나, 동작이 틀리면 고치지 않고 알린 뒤 다음 지시를 기다린다.
- 핵심 코드는 요청과 응답 데이터 클래스, 요청을 보내는 코드, 응답에서 값을 꺼내는 코드, 성공과 실패를 구분하는 코드다. 클라이언트 함수의 시그니처와 반환 타입, 실패의 표현 방식도 핵심 코드에 속한다.
- 실제 API 호출은 자동 테스트에 넣지 않는다. 자동 테스트는 `MockEngine`으로만 돈다.
- `scenarios` 명령은 정규식 응답기만 쓴다.
- 실험 모델은 온도와 `max_tokens`를 받는 비추론 소형 모델 가운데 가장 저렴한 것으로 고른다.

## Review Focus

- `OPENAI_API_KEY`가 없는 상태에서 모델 응답기로 채팅을 시작하면, 스택 추적 대신 키가 없다는 한국어 안내를 보여 주고 종료 코드 2로 끝나야 한다. 테스트는 Task 3의 `missingApiKeyIsReported`.
- 원문 출력을 켰을 때 `Authorization` 헤더 값이 화면에 나오면 안 된다. 테스트는 Task 3의 `rawOutputMasksAuthorization`.
- 응답의 `content`가 `null`이거나 빈 문자열이면, 채팅에 `null`이라는 글자를 찍거나 죽지 않고 내용이 비었다는 안내와 `finish_reason`을 보여 줘야 한다. 테스트는 Task 3의 `emptyContentShowsFinishReason`.
- 한국어 메시지가 요청 본문에서 깨지지 않고 그대로 나가야 한다. 테스트는 Task 2의 `koreanMessageSurvivesRequestBody`.
- 채팅 명령에 잘못된 값을 주면 채팅이 멈추지 않고 사용법을 보여 준 뒤 설정을 바꾸지 않아야 한다. 예를 들어 `/temperature 뜨겁게`나 `/max-tokens -1`이 그렇다. 테스트는 Task 3의 `invalidCommandValueKeepsSettings`.

---

### Task 1: 의존성과 실험 모델 확정

**Files:**
- Modify: `gradle/libs.versions.toml`, `framework/build.gradle.kts`, `example-shop/build.gradle.kts`
- Create: `framework/src/main/kotlin/aiagent/framework/model/` 디렉터리와 `framework/src/test/kotlin/aiagent/framework/model/` 디렉터리
- Create: `framework/src/main/kotlin/aiagent/framework/model/ModelPricing.kt`
- Test: `framework/src/test/kotlin/aiagent/framework/model/ModelPricingTest.kt`

**Interfaces:**
- Produces: `framework` 모듈이 Ktor 클라이언트, `ContentNegotiation`, kotlinx.serialization JSON, 직렬화 플러그인을 쓸 수 있다. 테스트에서 `ktor-client-mock`을 쓸 수 있다. 예제 모듈은 `framework`의 Ktor 타입을 `api` 의존성으로 받는다.
- Produces: `object ModelPricing { const val DEFAULT_MODEL: String; fun costUsd(model: String, inputTokens: Int, outputTokens: Int): Double? }`. 가격표에 없는 모델이면 `null`을 돌려준다. 원화 표시는 `const val KRW_PER_USD: Double` 하나로 환산한다.

- [ ] **Step 1: 실험 모델과 가격을 확인한다.** OpenAI 가격표와 모델 문서를 WebFetch로 읽고, 온도와 `max_tokens`를 받는 비추론 소형 모델 가운데 입력 토큰당 가격이 가장 낮은 모델을 고른다. 고른 모델과, 실험에서 바꿔 볼 상위 소형 모델 하나의 100만 토큰당 입력 가격과 출력 가격을 적는다. 고른 근거를 학습자에게 한 문단으로 보고한다.
- [ ] **Step 2: 의존성을 추가한다.** `libs.versions.toml`에 `ktor = "3.6.1"`, `serialization = "1.12.0"`과 위 Tech Stack의 라이브러리, `kotlin-serialization` 플러그인(버전은 `kotlin`을 참조)을 넣는다. `framework`에는 직렬화 플러그인과 `api(ktor-client-core)`, `implementation(ktor-client-cio, ktor-client-content-negotiation, ktor-serialization-kotlinx-json)`, `api(coroutines-core)`, `testImplementation(ktor-client-mock, coroutines-test)`를 넣는다. 예제 모듈에는 `testImplementation(ktor-client-mock)`을 더한다.
- [ ] **Step 3: 실패하는 테스트를 쓴다.**

```kotlin
class ModelPricingTest {
    @Test
    fun defaultModelHasPrice() =
        assertNotNull(ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000, 1_000))

    @Test
    fun costIsLinearInTokens() {
        val one = ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 1_000_000, 0)!!
        assertEquals(one * 2, ModelPricing.costUsd(ModelPricing.DEFAULT_MODEL, 2_000_000, 0)!!, 1e-9)
    }

    @Test
    fun unknownModelHasNoPrice() = assertNull(ModelPricing.costUsd("no-such-model", 1, 1))
}
```

- [ ] **Step 4: 실행해서 실패를 확인한다.** `./gradlew :framework:test`가 `ModelPricing`을 찾지 못해 컴파일에 실패해야 한다.
- [ ] **Step 5: `ModelPricing`을 구현한다.** Step 1에서 확인한 두 모델의 가격을 맵 하나에 상수로 둔다.
- [ ] **Step 6: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 하고 기존 테스트 29개도 통과해야 한다.
- [ ] **Step 7: 커밋한다.** 메시지는 `모델 호출 의존성과 실험 모델 가격을 추가한다`.

### Task 2: 핵심 호출 코드

이 Task는 학습자의 지시로 진행한다. Claude는 이 Task의 코드 내용을 스스로 정하지 않는다.

**Files:**
- Create: 학습자가 정한 이름의 파일들을 `framework/src/main/kotlin/aiagent/framework/model/` 아래에 둔다.
- Test: `framework/src/test/kotlin/aiagent/framework/model/` 아래에 학습자가 정한 클라이언트 이름을 딴 테스트 파일.
- Create: `framework/src/test/resources/openai/chat-completion-ok.json`, `framework/src/test/resources/openai/error-401.json`

**Interfaces:**
- Produces: 학습자가 정한 클라이언트 함수와 요청, 응답, 실패 타입. 이 Task가 끝나면 Claude는 정해진 이름과 시그니처를 이 계획의 Task 2 아래 "확정된 모양" 항목에 다섯 줄 안팎으로 적어 둔다. Task 3과 Task 4는 그 항목을 읽고 시작한다.
- Produces: 클라이언트는 `HttpClient`를 바깥에서 받을 수 있어야 한다. `MockEngine` 테스트와 Task 3의 원문 출력이 이 점에 기댄다. 학습자의 지시가 이 조건을 만족하지 않으면 그 사실을 알리고 지시를 기다린다.

- [ ] **Step 1: 시작 자료를 준비한다.** OpenAI 공식 문서의 Chat Completions 성공 응답 예시와 401 오류 예시를 위 두 JSON 파일로 저장한다. 학습자에게는 요청 본문의 필수 필드인 `model`과 `messages`, 이번 실험에 쓸 선택 필드인 `temperature`와 `max_tokens`, 그리고 응답 예시 원문을 보여 준다. 코드는 쓰지 않는다.
- [ ] **Step 2: 요청 데이터 클래스를 지시대로 옮긴다.** 학습자가 필드와 타입, 직렬화 이름을 지시하면 그대로 쓴다. 옮긴 뒤 각 필드가 JSON의 어디가 되는지 설명한다.
- [ ] **Step 3: 응답 데이터 클래스를 지시대로 옮긴다.** 학습자가 정의하지 않은 응답 필드를 어떻게 다룰지도 학습자가 정한다. `ignoreUnknownKeys`를 쓸지가 그 결정이다. 옮긴 뒤 이 클래스가 버리는 응답 필드 목록을 학습자에게 보여 준다.
- [ ] **Step 4: 클라이언트 함수를 지시대로 옮긴다.** 함수 시그니처, 헤더, 경로, 실패를 예외로 던질지 결과 타입으로 돌려줄지를 학습자가 정한다.
- [ ] **Step 5: 확정된 모양을 이 계획에 적는다.** 위 Interfaces의 첫 항목대로 적는다.
- [ ] **Step 6: 테스트를 쓴다.** 확정된 모양에 맞춰 다음 네 테스트를 쓴다. 이름은 고정이고, 검사하는 내용은 다음과 같다.
  - `sendsChatCompletionRequest`: `MockEngine`이 받은 요청의 메서드가 `POST`, 경로가 `/v1/chat/completions`, `Authorization` 헤더가 `Bearer test-key`, `Content-Type`이 `application/json`이다. 본문 JSON의 `model`과 `messages[0].role`, `messages[0].content`가 넘긴 값과 같다.
  - `parsesSuccessResponse`: `chat-completion-ok.json`을 돌려주면 내용 문자열, 입력 토큰 수, 출력 토큰 수, `finish_reason`이 그 파일의 값과 같다.
  - `surfacesErrorResponse`: 상태 401과 `error-401.json`을 돌려주면 성공으로 취급되지 않고, 결과에서 상태 코드 401과 파일의 `error.message`를 읽을 수 있다.
  - `koreanMessageSurvivesRequestBody`: 사용자 메시지 `"주문 ORD-0001 어디쯤 왔나요?"`를 보내면 `MockEngine`이 받은 본문을 다시 풀었을 때 같은 문자열이 나온다.
- [ ] **Step 7: 실행한다.** `./gradlew :framework:test`가 통과해야 한다. 실패하면 실패 내용과 원인을 학습자에게 보여 주고, 고칠 방법은 학습자의 지시를 받는다. 테스트가 틀린 것이면 Claude가 테스트를 고친다.
- [ ] **Step 8: 검토하고 설명한다.** 완성된 핵심 코드를 처음부터 읽으며 요청 하나가 나가고 응답 하나가 풀리기까지의 흐름을 설명한다. 학습자가 고르지 않은 동작이 기본값으로 숨어 있으면 짚는다. Ktor의 기본 시간 제한, 2xx가 아닌 응답에서 Ktor가 하는 일이 그 예다.
- [ ] **Step 9: 커밋한다.** 메시지는 `Chat Completions 호출 클라이언트를 추가한다`.

**확정된 모양:**
- 요청은 `ChatRequest(model: String, messages: List<ChatRequest.MessageRequest>, temperature: Double? = null, maxTokens: Int? = null)`이고, `MessageRequest(role: String, content: String)`이다. `maxTokens`는 JSON에서 `max_tokens`가 된다.
- 응답은 `ChatResponse`이고 예시 응답의 모든 필드를 담는다. 내용은 `choices[0].message.content: String?`, 종료 이유는 `choices[0].finishReason`, 토큰은 `usage.promptTokens`와 `usage.completionTokens`에서 읽는다.
- 인터페이스는 `ChatClient { suspend fun chat(request: ChatRequest): Result<ChatResponse> }`이고, 구현은 `OpenAiClient(httpClient: HttpClient, apiKey: String, baseUrl: String = "https://api.openai.com")`이다.
- 2xx가 아닌 응답은 `Result.failure(ChatApiException(status: Int, error: ErrorResponse?))`가 된다. 오류 본문이 JSON이 아니면 `error`는 `null`이다. 네트워크 예외도 `Result.failure`가 되고, `CancellationException`만 다시 던진다.
- JSON은 `ignoreUnknownKeys = true`로 푼다.

### Task 3: 모델 응답기와 채팅 연결

**Files:**
- Create: `framework/src/main/kotlin/aiagent/framework/model/RawExchangeLog.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/agent/ModelResponder.kt`
- Create: `example-shop/src/main/kotlin/aiagent/shop/cli/ChatCommands.kt`
- Modify: `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Test: `framework/src/test/kotlin/aiagent/framework/model/RawExchangeLogTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/agent/ModelResponderTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ChatCommandsTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ArgsTest.kt`

**Interfaces:**
- Consumes: Task 2의 확정된 모양, Task 1의 `ModelPricing`.
- Produces: `class RawExchangeLog`는 `HttpClient`에 설치하는 Ktor 클라이언트 플러그인 또는 응답 본문을 한 번 더 읽는 방식 가운데 하나로 구현한다. 켜져 있으면 요청 줄, 헤더, 본문과 응답 상태, 본문을 `(String) -> Unit`으로 내보내고, `Authorization` 값은 `Bearer ***`로 바꾼다.
- Produces: `data class ModelSettings(val model: String, val temperature: Double?, val maxTokens: Int?, val showRaw: Boolean)`. `temperature`와 `maxTokens`가 `null`이면 요청에서 그 필드를 보내지 않는다.
- Produces: `class ModelResponder(client: <Task 2의 클라이언트>, settings: () -> ModelSettings, systemPrompt: String) : Responder`와 `fun reset()`, `val lastUsage: <입력 토큰, 출력 토큰, 추정 비용>`. 성공한 턴만 사용자 메시지와 응답을 대화 기록에 더한다.
- Produces: `fun applyChatCommand(line: String, settings: ModelSettings): ChatCommandResult`. 결과는 바뀐 설정과 보여 줄 문구를 담는다. 명령은 `/model <이름>`, `/temperature <0.0~2.0|off>`, `/max-tokens <양의 정수|off>`, `/raw <on|off>`, `/reset`, `/settings`이다.
- Produces: `CliArgs`에 `responder: String` 필드를 더한다. 값은 `rule` 또는 `model`이고 기본값은 `rule`이다. 옵션은 `--responder`이고 `chat`에서만 받는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**
  - `RawExchangeLogTest.rawOutputMasksAuthorization`: `MockEngine`으로 `Authorization: Bearer sk-secret` 요청을 보내면 내보낸 문자열에 `sk-secret`이 없고 `Bearer ***`와 요청 본문, 응답 본문이 있다.
  - `ModelResponderTest.historyGrowsOnlyOnSuccess`: 첫 호출 성공, 둘째 호출 429, 셋째 호출 성공으로 응답하는 `MockEngine`에서 세 번 `respond`하면, 셋째 요청 본문의 `messages`는 시스템 메시지, 첫째 사용자 메시지, 첫째 응답, 셋째 사용자 메시지 순서의 네 개다.
  - `ModelResponderTest.resetClearsHistory`: 한 번 대화한 뒤 `reset()`하고 다시 보내면 요청의 `messages`가 시스템 메시지와 새 사용자 메시지 두 개다.
  - `ModelResponderTest.emptyContentShowsFinishReason`: `content`가 `null`이고 `finish_reason`이 `length`인 응답이면 `respond`의 결과에 `null`이 없고 `length`가 들어 있다.
  - `ModelResponderTest.offSettingsAreOmitted`: `temperature`와 `maxTokens`가 `null`이면 요청 본문에 `temperature`와 `max_tokens` 키가 없다.
  - `ChatCommandsTest.invalidCommandValueKeepsSettings`: `/temperature 뜨겁게`, `/temperature 3`, `/max-tokens -1`, `/max-tokens 0`마다 설정이 그대로이고 문구에 사용법이 들어 있다.
  - `ChatCommandsTest.validCommandsChangeSettings`: `/temperature 0.7`, `/max-tokens off`, `/model gpt-x`, `/raw on`이 각각 해당 값만 바꾼다.
  - `ArgsTest`: 기존 기대값에 `responder = "rule"`을 반영하고, `chat --responder model`과 `scenarios --responder model` 거부를 더한다.
  - `ArgsTest.missingApiKeyIsReported`: 환경 변수 조회를 `(String) -> String?`로 받는 함수 `requireApiKey(env: (String) -> String?): String`가 `null`일 때 메시지에 `OPENAI_API_KEY`가 들어간 `IllegalArgumentException`을 던진다.
- [ ] **Step 2: 실행해서 실패를 확인한다.** `./gradlew test`가 새 타입을 찾지 못해 실패해야 한다.
- [ ] **Step 3: 구현한다.** `RawExchangeLog`, `ModelResponder`, `applyChatCommand`, `requireApiKey`를 구현하고 `Main.kt`의 채팅 루프를 고친다. 채팅 루프는 `/`로 시작하는 줄을 명령으로 처리한다. 모델 응답 뒤에는 `[토큰] 입력 N · 출력 M · 약 X원` 한 줄을 보여 준다. 가격표에 없는 모델이면 비용 자리에 `가격 미등록`을 보여 준다. 호출 실패는 `[모델 오류] <상태 코드> <메시지>` 한 줄로 보여 주고 다음 입력을 받는다. `requireApiKey`가 던지면 `main`의 기존 처리대로 메시지와 사용법을 보여 주고 종료 코드 2로 끝낸다. 시스템 프롬프트는 쇼핑몰 상담원 역할을 한국어 두세 문장으로 적고, 주문 정보는 넣지 않는다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 손으로 한 번 확인한다.** 학습자에게 `./gradlew :example-shop:run --args="chat --responder model"`을 직접 실행하도록 안내하고 `안녕하세요` 한 번을 보내 응답과 토큰 줄이 보이는지 확인한다. 이 호출이 첫 실험의 재료가 되므로, 학습자가 먼저 예측을 적은 뒤 실행한다.
- [ ] **Step 6: 커밋한다.** 메시지는 `모델 응답기와 채팅 실험 명령을 추가한다`.

### Task 4: 몰아 보내기 명령

**Files:**
- Create: `example-shop/src/main/kotlin/aiagent/shop/cli/Burst.kt`
- Modify: `example-shop/src/main/kotlin/aiagent/shop/cli/Main.kt`
- Test: `example-shop/src/test/kotlin/aiagent/shop/cli/BurstTest.kt`, `example-shop/src/test/kotlin/aiagent/shop/cli/ArgsTest.kt`

**Interfaces:**
- Consumes: Task 2의 확정된 모양, Task 3의 `requireApiKey`.
- Produces: `suspend fun burst(count: Int, concurrency: Int, send: suspend () -> Int): Map<Int, Int>`. `send`는 요청 하나를 보내고 HTTP 상태 코드를 돌려준다. 결과는 상태 코드별 건수다. 명령은 `burst --count <정수> --concurrency <정수> [--model <이름>]`이고, 기본값은 `count` 20, `concurrency` 10이다. 요청마다 `max_tokens` 1과 짧은 메시지를 보내서 비용을 줄인다. 첫 429 응답의 본문과 응답 헤더 가운데 `x-ratelimit-`로 시작하는 헤더와 `retry-after`를 출력한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.**
  - `BurstTest.countsStatusCodes`: 요청 순서대로 200, 429, 200, 429, 429를 돌려주는 `send`로 `burst(5, 2, send)`를 부르면 `{200=2, 429=3}`이다.
  - `BurstTest.respectsConcurrency`: 동시에 실행 중인 `send` 수의 최댓값을 세면 `concurrency` 3일 때 3을 넘지 않는다.
  - `ArgsTest`: `burst --count 0` 거부와 `burst --count 1000` 거부를 더한다. `count`의 상한은 100으로 둔다.
- [ ] **Step 2: 실행해서 실패를 확인한다.**
- [ ] **Step 3: 구현한다.** 동시 실행 수는 `Semaphore`로 제한한다.
- [ ] **Step 4: 실행해서 통과를 확인한다.** `./gradlew test`가 `BUILD SUCCESSFUL`이어야 한다.
- [ ] **Step 5: 커밋한다.** 메시지는 `429 실험용 몰아 보내기 명령을 추가한다`.

### Task 5: 실험과 학습 기록

이 Task는 코드 작성이 아니라 학습자와 함께하는 실험이다.

**Files:**
- Create: `docs/learning/stage1-model-call.md`. 학습자가 쓰고 Claude는 학습자가 지시한 내용만 옮긴다.
- Modify: `framework/src/test/resources/openai/chat-completion-ok.json`

- [ ] **Step 1: 학습 기록 틀을 만든다.** 설계 문서의 실험 여덟 개를 소제목으로 두고, 각 소제목 아래에 예측, 실제 결과, 달랐던 이유 자리를 둔다.
- [ ] **Step 2: 실험마다 같은 순서로 진행한다.** 학습자가 예측을 말하면 기록에 옮긴다. 학습자가 채팅이나 `burst` 명령을 직접 실행하고, 원문 출력을 켜서 결과를 함께 본다. 학습자가 실제 결과와 달랐던 이유를 말하면 기록에 옮기고, Claude는 틀린 설명이나 빠진 부분을 지적한다.
- [ ] **Step 3: 테스트 고정 데이터를 실제 응답으로 바꾼다.** 첫 실험의 응답 원문으로 `chat-completion-ok.json`을 바꾸고, Task 2의 `parsesSuccessResponse` 기대값을 그 파일의 값에 맞춘다. `./gradlew :framework:test`가 통과해야 한다.
- [ ] **Step 4: 429를 겪지 못한 경우를 기록한다.** `burst`로도 429가 나지 않으면 시도한 `count`와 `concurrency`, 받은 `x-ratelimit-` 헤더 값을 기록하고 후반부의 지연 프록시 과제로 넘긴다.
- [ ] **Step 5: 비용을 확인한다.** 이번 주 실험의 누적 토큰과 추정 비용을 기록에 적고, OpenAI 사용량 화면의 값과 비교하도록 학습자에게 안내한다.
- [ ] **Step 6: 커밋한다.** 메시지는 `모델 호출 실험 기록을 추가한다`.

### Task 6: 마지막 확인

- [ ] **Step 1: 과제를 낸다.** 다음 Spring AI 코드를 보여 주고, 학습자가 아무것도 보지 않고 이 코드가 만드는 HTTP 요청 줄, 헤더, 본문과 응답 본문을 말하게 한다.

```kotlin
val answer = chatClient.prompt()
    .system("너는 쇼핑몰 상담원이다.")
    .user("배송은 보통 며칠 걸리나요?")
    .options(OpenAiChatOptions.builder().model(DEFAULT_MODEL).temperature(0.2).maxTokens(100).build())
    .call()
    .content()
```

- [ ] **Step 2: 비교한다.** 같은 내용을 채팅에서 원문 출력을 켜고 보낸다. 학습자의 답과 실제 원문을 비교하고, 맞지 않은 부분을 학습 기록의 마지막 절에 옮긴다. `content()`가 버린 필드를 학습자가 모두 말했는지도 확인한다.
- [ ] **Step 3: 후반부의 문제를 정리한다.** 이번 주에 겪은 실패와 불편을 학습 기록 끝에 학습자의 말로 정리한다. 이 목록이 후반부 설계의 출발점이다.
- [ ] **Step 4: 커밋한다.** 메시지는 `모델 호출 전반부 마지막 확인을 기록한다`.
