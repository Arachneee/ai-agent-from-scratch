package aiagent.shop.cli

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.model.ModelPricing
import aiagent.shop.ShopWorld
import aiagent.shop.agent.ModelResponder
import aiagent.shop.agent.ModelSettings
import aiagent.shop.agent.ShopTools
import aiagent.shop.fault.ServiceException
import aiagent.shop.scenario.Responder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ChatTurnTest {
    @Test
    fun chatTurnSurvivesUnexpectedException() = runTest {
        val out = mutableListOf<String>()

        runChatTurn(Responder { throw IllegalStateException("깨짐") }, "안녕", out::add)

        assertEquals(listOf("[오류] IllegalStateException: 깨짐"), out)
    }

    @Test
    fun serviceExceptionIsShownAsServiceError() = runTest {
        val out = mutableListOf<String>()

        runChatTurn(Responder { throw ServiceException.NotFound("주문", "ORD-9999") }, "안녕", out::add)

        assertEquals(listOf("[서비스 오류] 주문 ORD-9999 없음"), out)
    }

    @Test
    fun timingLineReportsFirstOutputAndTotal() = runTest {
        val time = TestTimeSource()
        val client = AdvancingChatClient(time, listOf(1.seconds to toolCall("확인해 볼게요"), 2.seconds to answer("출고 대기입니다.")))
        val settings = ModelSettings(ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false)
        val responder = ModelResponder(client, ShopTools(ShopWorld.create(42)), { settings })
        val out = mutableListOf<String>()

        runChatTurn(responder, "ORD-0001 배송 상태 알려주세요.", out::add, time)

        assertEquals("[시간] 첫 출력 1.0초 · 전체 3.0초", out.last())
    }

    /** 요청을 받을 때마다 정해 둔 만큼 시간을 진행한 뒤 응답을 돌려준다. */
    private class AdvancingChatClient(
        private val time: TestTimeSource,
        private val steps: List<Pair<Duration, ChatResponse>>,
    ) : ChatClient {
        private var calls = 0

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            val (elapsed, response) = steps[calls++]
            time += elapsed
            return Result.success(response)
        }
    }

    private companion object {
        fun toolCall(content: String) = response(
            ChatResponse.MessageResponse(
                "assistant", content, null, emptyList(),
                listOf(ChatResponse.ToolCall("call_1", "function", ChatResponse.FunctionCall("get_shipment", """{"order_id":"ORD-0001"}"""))),
            ),
            "tool_calls",
        )

        fun answer(content: String) = response(ChatResponse.MessageResponse("assistant", content, null, emptyList()), "stop")

        fun response(message: ChatResponse.MessageResponse, finishReason: String) = ChatResponse(
            id = "chatcmpl-test",
            objectType = "chat.completion",
            created = 0,
            model = "gpt-4o-mini-2024-07-18",
            choices = listOf(ChatResponse.Choice(0, message, null, finishReason)),
            usage = ChatResponse.Usage(10, 5, 15, ChatResponse.PromptTokensDetails(0, 0), ChatResponse.CompletionTokensDetails(0, 0, 0, 0)),
            serviceTier = "default",
            systemFingerprint = "fp_test",
        )
    }
}
