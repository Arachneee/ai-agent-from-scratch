package aiagent.framework.agent

import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatChunk
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.tool.Description
import aiagent.framework.tool.ToolCatalog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    @Serializable
    data class OrderIdArgs(@SerialName("order_id") @Description("주문번호") val orderId: String)

    private val executed = mutableListOf<String>()

    private val catalog = ToolCatalog().apply {
        register<OrderIdArgs, String>("get_order", "주문 상태를 조회한다.") { executed += "get_order"; "PREPARING" }
        register<OrderIdArgs, String>("get_shipment", "배송 상태를 조회한다.") { executed += "get_shipment"; "READY" }
    }

    private val settings = AgentSettings(model = "gpt-4o-mini", toolNames = listOf("get_order", "get_shipment"))

    @Test
    fun loopSendsToolResultThenStops() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_shipment")), answer("출고 대기입니다.")))

        val result = agent(client).run("어디쯤 왔어요?")

        assertEquals(2, client.requests.size)
        val second = client.requests[1].messages
        val assistant = second[second.size - 2]
        val tool = second.last()
        assertEquals("assistant", assistant.role)
        assertEquals("call_1", assistant.toolCalls!!.single().id)
        assertEquals("tool", tool.role)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("\"READY\"", tool.content)
        assertEquals("출고 대기입니다.", result.answer)
    }

    @Test
    fun nextTurnKeepsToolMessagesInOrder() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_shipment")), answer("출고 대기입니다."), answer("네.")))
        val agent = agent(client)

        agent.run("어디쯤 왔어요?")
        agent.run("고마워요.")

        assertEquals(
            listOf("system", "user", "assistant", "tool", "assistant", "user"),
            client.requests[2].messages.map { it.role },
        )
    }

    @Test
    fun executesEachToolCallInOrder() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_order"), call("call_2", "get_shipment")), answer("답")))

        val result = agent(client).run("주문 상태랑 배송 상태 알려 주세요.")

        assertEquals(listOf("get_order", "get_shipment"), executed)
        val tools = client.requests[1].messages.filter { it.role == "tool" }
        assertEquals(listOf("call_1", "call_2"), tools.map { it.toolCallId })
        assertEquals(
            listOf(
                ExecutedToolCall("get_order", ARGS, "\"PREPARING\""),
                ExecutedToolCall("get_shipment", ARGS, "\"READY\""),
            ),
            result.toolCalls,
        )
    }

    @Test
    fun usageIsCollectedPerRequest() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_shipment"), promptTokens = 150), answer("답", promptTokens = 180)))

        val result = agent(client).run("어디쯤 왔어요?")

        assertEquals(listOf(150, 180), result.usages.map { it.promptTokens })
    }

    @Test
    fun toolExceptionBecomesToolResult() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_refund")), answer("죄송합니다.")))

        val result = agent(client).run("환불해 주세요.")

        val tool = client.requests[1].messages.last()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("[툴 오류] IllegalArgumentException: 등록되지 않은 툴: get_refund", tool.content)
        assertEquals("죄송합니다.", result.answer)
    }

    @Test
    fun failedTurnStaysInHistory() = runTest {
        val client = FakeChatClient(listOf(Result.failure(ChatApiException(429, null)), answer("답")))
        val agent = agent(client)

        assertFailsWith<ChatApiException> { agent.run("첫 질문") }
        agent.run("둘째 질문")

        assertEquals(
            listOf("system" to SYSTEM, "user" to "첫 질문", "user" to "둘째 질문"),
            client.requests[1].messages.map { it.role to it.content },
        )
    }

    @Test
    fun toolProgressIsReportedBeforeEachExecution() = runTest {
        val events = mutableListOf<String>()
        val catalog = ToolCatalog().apply {
            register<OrderIdArgs, String>("get_order", "주문 상태를 조회한다.") { events += "execute get_order"; "PREPARING" }
            register<OrderIdArgs, String>("get_shipment", "배송 상태를 조회한다.") { events += "execute get_shipment"; "READY" }
        }
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_order"), call("call_2", "get_shipment")), answer("답")))

        Agent(client, catalog, { settings }, SYSTEM).run("어디쯤 왔어요?") {
            if (it is AgentEvent.ToolCallStarted) events += "started ${it.name} ${it.arguments}"
        }

        assertEquals(
            listOf("started get_order $ARGS", "execute get_order", "started get_shipment $ARGS", "execute get_shipment"),
            events,
        )
    }

    @Test
    fun textBeforeToolCallsIsStreamedAndKeptInHistory() = runTest {
        val events = mutableListOf<String>()
        val catalog = ToolCatalog().apply {
            register<OrderIdArgs, String>("get_shipment", "배송 상태를 조회한다.") { events += "execute"; "READY" }
        }
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_shipment"), content = "다시 한 번 시도해 보겠습니다."), answer("출고 대기입니다.")))

        Agent(client, catalog, { settings.copy(toolNames = listOf("get_shipment")) }, SYSTEM).run("어디쯤 왔어요?") {
            if (it is AgentEvent.TextDelta) events += "text ${it.text}"
        }

        assertEquals(listOf("text 다시 한 번 시도해 보겠습니다.", "execute", "text 출고 대기입니다."), events)
        val assistant = client.requests[1].messages[2]
        assertEquals("다시 한 번 시도해 보겠습니다.", assistant.content)
        assertEquals("call_1", assistant.toolCalls!!.single().id)
    }

    @Test
    fun textDeltasAreReportedInArrivalOrderWithoutEmptyPieces() = runTest {
        val texts = mutableListOf<String>()
        val client = FakeChatClient(listOf(answer("배송 준비 중입니다.")), pieces = { listOf("", "배송 ", "준비 중입니다.") })

        val result = agent(client).run("어디쯤 왔어요?") { if (it is AgentEvent.TextDelta) texts += it.text }

        assertEquals(listOf("배송 ", "준비 중입니다."), texts)
        assertEquals("배송 준비 중입니다.", result.answer)
    }

    @Test
    fun blockedCallIsNotReportedAsStarted() = runTest {
        val started = mutableListOf<String>()
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_order"), call("call_2", "get_order")), answer("답")))

        agent(client, settings.copy(maxCallsPerTool = 1)).run("어디쯤 왔어요?") {
            if (it is AgentEvent.ToolCallStarted) started += it.name
        }

        assertEquals(listOf("get_order"), started)
    }

    @Test
    fun toolAtLimitIsRemovedFromToolList() = runTest {
        val shipment = { id: String -> toolCalls(call(id, "get_shipment")) }
        val client = FakeChatClient(listOf(shipment("call_1"), shipment("call_2"), answer("답")))

        agent(client, settings.copy(maxCallsPerTool = 2)).run("어디쯤 왔어요?")

        assertEquals(listOf("get_order", "get_shipment"), client.requests[1].tools!!.map { it.function.name })
        assertEquals(listOf("get_order"), client.requests[2].tools!!.map { it.function.name })
    }

    @Test
    fun callBeyondLimitIsNotExecuted() = runTest {
        val client = FakeChatClient(
            listOf(toolCalls(call("call_1", "get_shipment"), call("call_2", "get_shipment")), toolCalls(call("call_3", "get_shipment")), answer("답")),
        )

        val result = agent(client, settings.copy(maxCallsPerTool = 1)).run("어디쯤 왔어요?")

        assertEquals(listOf("get_shipment"), executed)
        val blocked = "[툴 오류] get_shipment는 이번 턴에 더 쓸 수 없습니다"
        assertEquals(listOf("\"READY\"", blocked), client.requests[1].messages.filter { it.role == "tool" }.map { it.content })
        assertEquals(blocked, client.requests[2].messages.last().content)
        assertEquals("call_3", client.requests[2].messages.last().toolCallId)
        assertEquals(listOf("\"READY\"", blocked, blocked), result.toolCalls.map { it.result })
    }

    @Test
    fun exhaustingEveryToolOmitsToolsKey() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_order")), toolCalls(call("call_2", "get_shipment")), answer("답")))

        agent(client, settings.copy(maxCallsPerTool = 1)).run("주문이랑 배송 알려 주세요.")

        assertNull(client.requests[2].tools)
    }

    @Test
    fun limitResetsEveryTurn() = runTest {
        val client = FakeChatClient(listOf(toolCalls(call("call_1", "get_shipment")), answer("답"), answer("답")))
        val agent = agent(client, settings.copy(maxCallsPerTool = 1))

        agent.run("어디쯤 왔어요?")
        agent.run("다시 알려 주세요.")

        assertEquals(listOf("get_order", "get_shipment"), client.requests[2].tools!!.map { it.function.name })
    }

    @Test
    fun nullLimitAllowsAnyNumberOfCalls() = runTest {
        val calls = (1..5).map { toolCalls(call("call_$it", "get_shipment")) }
        val client = FakeChatClient(calls + answer("답"))

        agent(client, settings.copy(maxCallsPerTool = null)).run("어디쯤 왔어요?")

        assertEquals(5, executed.size)
    }

    @Test
    fun noToolNamesOmitsToolsKey() = runTest {
        val client = FakeChatClient(listOf(answer("답")))

        agent(client, settings.copy(toolNames = emptyList())).run("안녕하세요.")

        assertNull(client.requests[0].tools)
        assertFalse("tools" in Json.encodeToString(ChatRequest.serializer(), client.requests[0]))
    }

    @Test
    fun emptyContentShowsFinishReason() = runTest {
        val result = agent(FakeChatClient(listOf(answer(null, finishReason = "length")))).run("길게 말해 줘")

        assertTrue("length" in result.answer, result.answer)
    }

    @Test
    fun resetClearsHistory() = runTest {
        val client = FakeChatClient(listOf(answer("답"), answer("새 답")))
        val agent = agent(client)

        agent.run("이전 질문")
        agent.reset()
        agent.run("새 질문")

        assertEquals(listOf("system", "user"), client.requests[1].messages.map { it.role })
    }

    private fun agent(client: ChatClient, current: AgentSettings = settings) = Agent(client, catalog, { current }, SYSTEM)

    /**
     * 정해 둔 응답을 차례로 돌려준다. [chatStream]은 응답의 글자를 [pieces]로 나눈 조각을 먼저 넘긴 뒤 응답을 돌려준다.
     * 기본으로는 글자 전체를 조각 하나로 넘긴다.
     */
    private class FakeChatClient(
        private val results: List<Result<ChatResponse>>,
        private val pieces: (String) -> List<String> = { listOf(it) },
    ) : ChatClient {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            requests += request
            return results[requests.size - 1]
        }

        override suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse {
            val response = chat(request).getOrThrow()
            response.choices.single().message.content?.let(pieces)?.forEach { text ->
                onChunk(ChatChunk(response.id, 0, response.model, listOf(ChatChunk.Choice(0, ChatChunk.Delta(content = text), null)), null, "default", "fp_test"))
            }
            return response
        }
    }

    private companion object {
        const val SYSTEM = "시스템 프롬프트"
        const val ARGS = """{"order_id":"ORD-0001"}"""

        fun call(id: String, name: String) = ChatResponse.ToolCall(id, "function", ChatResponse.FunctionCall(name, ARGS))

        fun toolCalls(vararg calls: ChatResponse.ToolCall, promptTokens: Int = 10, content: String? = null) =
            response(ChatResponse.MessageResponse("assistant", content, null, emptyList(), calls.toList()), "tool_calls", promptTokens)

        fun answer(content: String?, finishReason: String = "stop", promptTokens: Int = 10) =
            response(ChatResponse.MessageResponse("assistant", content, null, emptyList()), finishReason, promptTokens)

        fun response(message: ChatResponse.MessageResponse, finishReason: String, promptTokens: Int) = Result.success(
            ChatResponse(
                id = "chatcmpl-test",
                objectType = "chat.completion",
                created = 0,
                model = "gpt-4o-mini-2024-07-18",
                choices = listOf(ChatResponse.Choice(0, message, null, finishReason)),
                usage = ChatResponse.Usage(
                    promptTokens, 5, promptTokens + 5,
                    ChatResponse.PromptTokensDetails(0, 0),
                    ChatResponse.CompletionTokensDetails(0, 0, 0, 0),
                ),
                serviceTier = "default",
                systemFingerprint = "fp_test",
            ),
        )
    }
}
