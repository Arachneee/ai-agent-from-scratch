package aiagent.shop.agent

import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelResponderTest {
    private val settings = ModelSettings(model = "gpt-4o-mini", temperature = null, maxTokens = null, showRaw = false)

    @Test
    fun historyGrowsOnlyOnSuccess() = runTest {
        val client = FakeChatClient(listOf(ok("첫 답"), rateLimited(), ok("셋째 답")))
        val responder = ModelResponder(client, { settings }, SYSTEM)

        responder.respond("첫 질문")
        responder.respond("둘째 질문")
        responder.respond("셋째 질문")

        assertEquals(
            listOf("system" to SYSTEM, "user" to "첫 질문", "assistant" to "첫 답", "user" to "셋째 질문"),
            client.requests[2].messages.map { it.role to it.content },
        )
    }

    @Test
    fun failureIsShownAndUsageCleared() = runTest {
        val responder = ModelResponder(FakeChatClient(listOf(ok("답"), rateLimited())), { settings }, SYSTEM)

        responder.respond("하나")
        val reply = responder.respond("둘")

        assertTrue(reply.startsWith("[모델 오류] 429"), reply)
        assertNull(responder.lastUsage)
    }

    @Test
    fun resetClearsHistory() = runTest {
        val client = FakeChatClient(listOf(ok("답"), ok("새 답")))
        val responder = ModelResponder(client, { settings }, SYSTEM)

        responder.respond("이전 질문")
        responder.reset()
        responder.respond("새 질문")

        assertEquals(listOf("system", "user"), client.requests[1].messages.map { it.role })
        assertEquals("새 질문", client.requests[1].messages[1].content)
    }

    @Test
    fun emptyContentShowsFinishReason() = runTest {
        val responder = ModelResponder(FakeChatClient(listOf(ok(null, finishReason = "length"))), { settings }, SYSTEM)

        val reply = responder.respond("길게 말해 줘")

        assertFalse("null" in reply, reply)
        assertTrue("length" in reply, reply)
    }

    @Test
    fun offSettingsAreOmitted() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        ModelResponder(client, { settings }, SYSTEM).respond("안녕")

        val body = Json.encodeToString(ChatRequest.serializer(), client.requests[0])
        assertFalse("temperature" in body, body)
        assertFalse("max_tokens" in body, body)
    }

    @Test
    fun settingsAreSentAndUsageIsRecorded() = runTest {
        val client = FakeChatClient(listOf(ok("답", promptTokens = 1_000_000, completionTokens = 0)))
        val responder = ModelResponder(client, { settings.copy(temperature = 0.7, maxTokens = 50) }, SYSTEM)

        responder.respond("안녕")

        assertEquals(0.7, client.requests[0].temperature)
        assertEquals(50, client.requests[0].maxTokens)
        val usage = responder.lastUsage!!
        assertEquals(1_000_000, usage.inputTokens)
        assertEquals(0.15, usage.costUsd!!, 1e-9)
    }

    @Test
    fun toolsAreSentWhenEnabled() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        ModelResponder(client, { settings.copy(toolsEnabled = true) }, SYSTEM).respond("안녕")

        assertEquals(listOf("get_order", "get_shipment"), client.requests[0].tools!!.map { it.function.name })
    }

    @Test
    fun toolsOffOmitsToolsKey() = runTest {
        val client = FakeChatClient(listOf(ok("답")))
        ModelResponder(client, { settings.copy(toolsEnabled = false) }, SYSTEM).respond("안녕")

        val body = Json.encodeToString(ChatRequest.serializer(), client.requests[0])
        assertFalse("tools" in body, body)
    }

    private class FakeChatClient(private val results: List<Result<ChatResponse>>) : ChatClient {
        val requests = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            requests += request
            return results[requests.size - 1]
        }
    }

    private companion object {
        const val SYSTEM = "시스템 프롬프트"

        fun rateLimited(): Result<ChatResponse> = Result.failure(ChatApiException(429, null))

        fun ok(content: String?, finishReason: String = "stop", promptTokens: Int = 10, completionTokens: Int = 5) =
            Result.success(
                ChatResponse(
                    id = "chatcmpl-test",
                    objectType = "chat.completion",
                    created = 0,
                    model = "gpt-4.1-nano-2025-04-14",
                    choices = listOf(
                        ChatResponse.Choice(0, ChatResponse.MessageResponse("assistant", content, null, emptyList()), null, finishReason),
                    ),
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
