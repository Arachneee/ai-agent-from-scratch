package aiagent.framework.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiClientTest {
    private val request = ChatRequest(
        model = "gpt-4.1-nano",
        messages = listOf(ChatRequest.MessageRequest(role = "user", content = "안녕하세요")),
    )

    @Test
    fun sendsChatCompletionRequest() = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        client { req -> captured = req; ok() }.chat(request)

        val req = captured!!
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer test-key", req.headers[HttpHeaders.Authorization])
        assertEquals("application/json", req.body.contentType?.withoutParameters()?.toString())
        val body = Json.parseToJsonElement(bodyText(req.body)).jsonObject
        assertEquals("gpt-4.1-nano", body["model"]!!.jsonPrimitive.content)
        val first = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        assertEquals("안녕하세요", first["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesSuccessResponse() = runTest {
        val response = client { ok() }.chat(request).getOrThrow()

        assertEquals("안녕하세요! 무엇을 도와드릴까요?", response.choices[0].message.content)
        assertEquals(54, response.usage.promptTokens)
        assertEquals(11, response.usage.completionTokens)
        assertEquals("stop", response.choices[0].finishReason)
    }

    @Test
    fun parsesToolCallResponse() = runTest {
        val response = client { respond(fixture("chat-completion-tool-calls.json"), HttpStatusCode.OK, JSON_HEADERS) }
            .chat(request).getOrThrow()

        val call = response.choices[0].message.toolCalls!!.single()
        assertEquals("call_wmZpVWpDAVRahYjHIoYmuxm5", call.id)
        assertEquals("get_shipment", call.function.name)
        assertEquals("""{"order_id":"ORD-0001"}""", call.function.arguments)
        assertEquals("tool_calls", response.choices[0].finishReason)
    }

    @Test
    fun surfacesErrorResponse() = runTest {
        val result = client { respond(fixture("error-401.json"), HttpStatusCode.Unauthorized, JSON_HEADERS) }.chat(request)

        assertTrue(result.isFailure)
        val e = assertIs<ChatApiException>(result.exceptionOrNull())
        assertEquals(401, e.status)
        assertTrue(e.error!!.error.message.startsWith("Incorrect API key provided"))
    }

    @Test
    fun koreanMessageSurvivesRequestBody() = runTest {
        val text = "주문 ORD-0001 어디쯤 왔나요?"
        var sent: String? = null
        client { req -> sent = bodyText(req.body); ok() }
            .chat(request.copy(messages = listOf(ChatRequest.MessageRequest("user", text))))

        val decoded = Json.decodeFromString<ChatRequest>(sent!!)
        assertEquals(text, decoded.messages[0].content)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            client { started.complete(Unit); awaitCancellation() }.chat(request)
            returned = true
        }
        started.await()
        job.cancel()
        job.join()

        assertFalse(returned, "취소된 호출이 Result로 돌아와 다음 줄이 실행됐습니다")
    }

    private fun client(handler: MockRequestHandler) =
        OpenAiClient(HttpClient(MockEngine(handler)), apiKey = "test-key")

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok() =
        respond(fixture("chat-completion-ok.json"), HttpStatusCode.OK, JSON_HEADERS)

    private suspend fun bodyText(body: OutgoingContent) = body.toByteArray().decodeToString()

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name")!!.readText()

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
