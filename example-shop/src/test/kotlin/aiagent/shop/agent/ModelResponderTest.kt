package aiagent.shop.agent

import aiagent.framework.agent.AgentEvent
import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatChunk
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.shop.ShopWorld
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelResponderTest {
    private val settings = ModelSettings(model = "gpt-4o-mini", temperature = null, maxTokens = null, showRaw = false)
    private val tools = ShopTools(ShopWorld.create(42))

    @Test
    fun usagePerRequestIsReported() = runTest {
        val client = FakeChatClient(listOf(toolCall("get_shipment", promptTokens = 150), ok("출고 대기입니다.", promptTokens = 180)))
        val responder = responder(client)

        responder.respond("주문번호 ORD-0001 지금 어디쯤 왔어요?")

        assertEquals(listOf(150, 180), responder.lastUsages.map { it.inputTokens })
    }

    @Test
    fun answersFromToolResult() = runTest {
        val client = FakeChatClient(listOf(toolCall("get_shipment"), ok("출고 대기입니다.")))
        val responder = responder(client)

        val reply = responder.respond("주문번호 ORD-0001 지금 어디쯤 왔어요?")

        assertEquals("출고 대기입니다.", reply)
        assertEquals("get_shipment", responder.lastToolCalls.single().name)
        assertTrue("READY" in client.requests[1].messages.last().content!!)
    }

    @Test
    fun eventsArePassedToCaller() = runTest {
        val client = FakeChatClient(listOf(toolCall("get_shipment", content = "다시 한 번 시도해 보겠습니다."), ok("출고 대기입니다.")))
        val events = mutableListOf<AgentEvent>()

        responder(client).respond("주문번호 ORD-0001 지금 어디쯤 왔어요?", events::add)

        assertEquals(
            listOf(
                AgentEvent.TextDelta("다시 한 번 시도해 보겠습니다."),
                AgentEvent.ToolCallStarted("get_shipment", """{"order_id":"ORD-0001"}"""),
                AgentEvent.TextDelta("출고 대기입니다."),
            ),
            events,
        )
    }

    @Test
    fun toolsAreSentWhenEnabled() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        responder(client, settings.copy(toolsEnabled = true)).respond("안녕")

        assertEquals(listOf("get_order", "get_shipment"), client.requests[0].tools!!.map { it.function.name })
    }

    @Test
    fun toolsOffOmitsToolsKey() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        responder(client, settings.copy(toolsEnabled = false)).respond("안녕")

        val body = Json.encodeToString(ChatRequest.serializer(), client.requests[0])
        assertFalse("tools" in body, body)
    }

    @Test
    fun failureIsShownAndUsageCleared() = runTest {
        val responder = responder(FakeChatClient(listOf(ok("답"), rateLimited())))

        responder.respond("하나")
        val reply = responder.respond("둘")

        assertTrue(reply.startsWith("[모델 오류] 429"), reply)
        assertTrue(responder.lastUsages.isEmpty())
        assertTrue(responder.lastToolCalls.isEmpty())
    }

    @Test
    fun resetClearsHistory() = runTest {
        val client = FakeChatClient(listOf(ok("답"), ok("새 답")))
        val responder = responder(client)

        responder.respond("이전 질문")
        responder.reset()
        responder.respond("새 질문")

        assertEquals(listOf("system", "user"), client.requests[1].messages.map { it.role })
        assertEquals("새 질문", client.requests[1].messages[1].content)
    }

    @Test
    fun emptyContentShowsFinishReason() = runTest {
        val reply = responder(FakeChatClient(listOf(ok(null, finishReason = "length")))).respond("길게 말해 줘")

        assertFalse("null" in reply, reply)
        assertTrue("length" in reply, reply)
    }

    @Test
    fun offSettingsAreOmitted() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        responder(client).respond("안녕")

        val body = Json.encodeToString(ChatRequest.serializer(), client.requests[0])
        assertFalse("temperature" in body, body)
        assertFalse("max_tokens" in body, body)
    }

    @Test
    fun settingsAreSentAndUsageIsRecorded() = runTest {
        val client = FakeChatClient(listOf(ok("답", promptTokens = 1_000_000, completionTokens = 0)))
        val responder = responder(client, settings.copy(temperature = 0.7, maxTokens = 50))

        responder.respond("안녕")

        assertEquals(0.7, client.requests[0].temperature)
        assertEquals(50, client.requests[0].maxTokens)
        val usage = responder.lastUsages.single()
        assertEquals(1_000_000, usage.inputTokens)
        assertEquals(0.15, usage.costUsd!!, 1e-9)
    }

    private fun responder(client: ChatClient, current: ModelSettings = settings) = ModelResponder(client, tools, { current }, SYSTEM)

    private class FakeChatClient(private val results: List<Result<ChatResponse>>) : ChatClient {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            requests += request
            return results[requests.size - 1]
        }

        override suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse {
            val response = chat(request).getOrThrow()
            response.choices.single().message.content?.let { text ->
                onChunk(ChatChunk(response.id, 0, response.model, listOf(ChatChunk.Choice(0, ChatChunk.Delta(content = text), null)), null, "default", "fp_test"))
            }
            return response
        }
    }

    private companion object {
        const val SYSTEM = "시스템 프롬프트"

        fun rateLimited(): Result<ChatResponse> = Result.failure(ChatApiException(429, null))

        fun toolCall(name: String, promptTokens: Int = 10, content: String? = null) = response(
            ChatResponse.MessageResponse(
                "assistant", content, null, emptyList(),
                listOf(ChatResponse.ToolCall("call_1", "function", ChatResponse.FunctionCall(name, """{"order_id":"ORD-0001"}"""))),
            ),
            "tool_calls", promptTokens, 19,
        )

        fun ok(content: String?, finishReason: String = "stop", promptTokens: Int = 10, completionTokens: Int = 5) =
            response(ChatResponse.MessageResponse("assistant", content, null, emptyList()), finishReason, promptTokens, completionTokens)

        fun response(message: ChatResponse.MessageResponse, finishReason: String, promptTokens: Int, completionTokens: Int) =
            Result.success(
                ChatResponse(
                    id = "chatcmpl-test",
                    objectType = "chat.completion",
                    created = 0,
                    model = "gpt-4o-mini-2024-07-18",
                    choices = listOf(ChatResponse.Choice(0, message, null, finishReason)),
                    usage = ChatResponse.Usage(
                        promptTokens, completionTokens, promptTokens + completionTokens,
                        ChatResponse.PromptTokensDetails(0, 0),
                        ChatResponse.CompletionTokensDetails(0, 0, 0, 0),
                    ),
                    serviceTier = "default",
                    systemFingerprint = "fp_test",
                ),
            )
    }
}
