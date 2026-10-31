package aiagent.shop.scenario

import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatChunk
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.model.ModelPricing
import aiagent.shop.ShopWorld
import aiagent.shop.agent.ModelResponder
import aiagent.shop.agent.ModelSettings
import aiagent.shop.agent.ShopTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongChatTest {
    private val first = Checkpoint(2, listOf("ORD-0001", "준비 중", "출고 대기"))

    @Test
    fun turnInputTokensAreSummedPerTurn() = runTest {
        val client = FakeChatClient(listOf(toolCall(150), answer("출고 대기입니다.", 180), answer("네.", 200)))

        val report = runLongChat(responder(client), listOf("ORD-0001 어디쯤 왔어요?", "고마워요"), emptyList()) {}

        assertEquals(listOf(330, 200), report.turns.map { it.inputTokens })
    }

    @Test
    fun cachedTokensAreSummedPerTurn() = runTest {
        val client = FakeChatClient(listOf(toolCall(150, cached = 0), answer("출고 대기입니다.", 180, cached = 128), answer("네.", 200, cached = 128)))

        val report = runLongChat(responder(client), listOf("ORD-0001 어디쯤 왔어요?", "고마워요"), emptyList()) {}

        assertEquals(listOf(128, 128), report.turns.map { it.cachedTokens })
    }

    @Test
    fun modelErrorTurnIsMarkedAndRunContinues() = runTest {
        val client = FakeChatClient(listOf(answer("안녕하세요.", 10), Result.failure(ChatApiException(429, null)), answer("네.", 30)))

        val report = runLongChat(responder(client), listOf("안녕", "둘째", "셋째"), emptyList()) {}

        assertNull(report.turns[0].error)
        assertTrue(report.turns[1].error!!.startsWith("[모델 오류] 429"), report.turns[1].error)
        assertEquals(3, report.turns.size)
        assertEquals(30, report.turns[2].inputTokens)
    }

    @Test
    fun checkpointPassesWhenReplyHasAcceptedText() = runTest {
        val client = FakeChatClient(listOf(answer("안녕하세요.", 10), answer("출고 대기 중입니다.", 20)))

        val report = runLongChat(responder(client), listOf("안녕", "첫 번째 주문 어떻게 됐어요?"), listOf(first)) {}

        assertEquals(listOf(first), report.passed)
        assertTrue(report.failed.isEmpty())
    }

    @Test
    fun checkpointRejectsTheOtherOrdersStatus() = runTest {
        val client = FakeChatClient(listOf(answer("안녕하세요.", 10), answer("배송이 지연되고 있습니다.", 20)))

        val report = runLongChat(responder(client), listOf("안녕", "첫 번째 주문 어떻게 됐어요?"), listOf(first)) {}

        assertTrue(report.passed.isEmpty())
        assertEquals(listOf(first to "배송이 지연되고 있습니다."), report.failed)
    }

    @Test
    fun turnLineAndSummaryArePrinted() = runTest {
        val client = FakeChatClient(listOf(answer("안녕하세요.", 10, cached = 0), answer("출고 대기 중입니다.", 20)))
        val out = mutableListOf<String>()

        runLongChat(responder(client), listOf("안녕", "첫 번째 주문 어떻게 됐어요?"), listOf(first), out::add)

        assertTrue(out[0].startsWith("[턴 1] 입력 10 (캐시 0) · 약 "), out[0])
        assertTrue(out.any { it.startsWith("[요약] 마지막 턴 입력 20 · 전체 입력 30 (캐시 0)") }, out.toString())
        assertTrue(out.any { it == "[요약] 확인 지점 1/1 정답" }, out.toString())
    }

    @Test
    fun scriptShape() {
        assertTrue(LongChat.messages.size >= 41)
        val opening = LongChat.messages.take(3).joinToString(" ")
        assertTrue("ORD-0001" in opening && "ORD-0002" in opening)
        assertEquals(4, LongChat.checkpoints.size)
        LongChat.checkpoints.forEach { assertFalse("ORD-" in LongChat.messages[it.turn - 1], LongChat.messages[it.turn - 1]) }
        assertTrue(LongChat.checkpoints.all { it.turn > 30 })
        assertFalse(LongChat.checkpoints.any { it.turn == LongChat.messages.size })
    }

    private fun responder(client: ChatClient): ModelResponder {
        val settings = ModelSettings(ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false)
        return ModelResponder(client, ShopTools(ShopWorld.create(42)), { settings })
    }

    private class FakeChatClient(private val results: List<Result<ChatResponse>>) : ChatClient {
        private var calls = 0

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> = results[calls++]

        override suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse = chat(request).getOrThrow()
    }

    private companion object {
        fun toolCall(promptTokens: Int, cached: Int = 0) = response(
            ChatResponse.MessageResponse(
                "assistant", null, null, emptyList(),
                listOf(ChatResponse.ToolCall("call_1", "function", ChatResponse.FunctionCall("get_shipment", """{"order_id":"ORD-0001"}"""))),
            ),
            "tool_calls", promptTokens, cached,
        )

        fun answer(content: String, promptTokens: Int, cached: Int = 0) =
            response(ChatResponse.MessageResponse("assistant", content, null, emptyList()), "stop", promptTokens, cached)

        fun response(message: ChatResponse.MessageResponse, finishReason: String, promptTokens: Int, cached: Int) = Result.success(
            ChatResponse(
                id = "chatcmpl-test",
                objectType = "chat.completion",
                created = 0,
                model = "gpt-4o-mini-2024-07-18",
                choices = listOf(ChatResponse.Choice(0, message, null, finishReason)),
                usage = ChatResponse.Usage(
                    promptTokens, 5, promptTokens + 5,
                    ChatResponse.PromptTokensDetails(cached, 0),
                    ChatResponse.CompletionTokensDetails(0, 0, 0, 0),
                ),
                serviceTier = "default",
                systemFingerprint = "fp_test",
            ),
        )
    }
}
