package aiagent.framework.agent

import aiagent.framework.model.ChatChunk
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSummarizerTest {
    private val dropped = listOf(
        ChatRequest.MessageRequest("user", "주문번호는 A-1이에요."),
        ChatRequest.MessageRequest(
            "assistant", null,
            toolCalls = listOf(ChatResponse.ToolCall("call_1", "function", ChatResponse.FunctionCall("lookup", """{"id":"A-1"}"""))),
        ),
        ChatRequest.MessageRequest("tool", "\"READY\"", toolCallId = "call_1"),
        ChatRequest.MessageRequest("assistant", "준비 중입니다."),
    )

    @Test
    fun sendsInstructionAndRenderedHistoryWithoutTools() = runTest {
        val client = RecordingClient("새 요약")

        ModelSummarizer(client, "gpt-4o-mini", "지시문").summarize("이전 요약 내용", dropped)

        val request = client.requests.single()
        assertEquals("gpt-4o-mini", request.model)
        assertNull(request.tools)
        assertEquals("system" to "지시문", request.messages[0].let { it.role to it.content })
        val body = request.messages[1].content!!
        assertEquals("user", request.messages[1].role)
        listOf("이전 요약: 이전 요약 내용", "사용자: 주문번호는 A-1이에요.", """툴 호출: lookup {"id":"A-1"}""", "툴 결과: \"READY\"", "어시스턴트: 준비 중입니다.")
            .forEach { assertTrue(it in body, "$it 없음: $body") }
    }

    @Test
    fun returnsTextAndUsage() = runTest {
        val summary = ModelSummarizer(RecordingClient("새 요약"), "gpt-4o-mini").summarize(null, dropped)

        assertEquals("새 요약", summary.text)
        assertEquals(42, summary.usage!!.promptTokens)
    }

    @Test
    fun dropSummarizerForgetsEverything() = runTest {
        assertEquals(Summary(null, null), DropSummarizer.summarize("이전 요약", dropped))
    }

    private class RecordingClient(private val text: String) : ChatClient {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            requests += request
            return Result.success(
                ChatResponse(
                    "chatcmpl-test", "chat.completion", 0, "gpt-4o-mini-2024-07-18",
                    listOf(ChatResponse.Choice(0, ChatResponse.MessageResponse("assistant", text, null, emptyList()), null, "stop")),
                    ChatResponse.Usage(42, 5, 47, ChatResponse.PromptTokensDetails(0, 0), ChatResponse.CompletionTokensDetails(0, 0, 0, 0)),
                    "default", "fp_test",
                ),
            )
        }

        override suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse = chat(request).getOrThrow()
    }
}
