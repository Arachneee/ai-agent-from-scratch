package aiagent.framework.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenAiClientStreamTest {
    private val request = ChatRequest(
        model = "gpt-4o-mini",
        messages = listOf(ChatRequest.MessageRequest(role = "user", content = "안녕하세요.")),
    )

    @Test
    fun streamRequestEnablesStreamAndUsage() = runTest {
        var body: String? = null
        client { req -> body = req.body.toByteArray().decodeToString(); stream(fixture("chat-stream-text.txt")) }
            .chatStream(request) {}

        val json = Json.parseToJsonElement(body!!).jsonObject
        assertEquals(true, json["stream"]!!.jsonPrimitive.boolean)
        assertEquals(true, json["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun assemblesTextFromStream() = runTest {
        val response = client { stream(fixture("chat-stream-text.txt")) }.chatStream(request) {}

        val choice = response.choices.single()
        assertEquals("assistant", choice.message.role)
        assertEquals("안녕하세요! 어떻게 도와드릴까요?", choice.message.content)
        assertNull(choice.message.toolCalls)
        assertEquals("stop", choice.finishReason)
        assertEquals("chatcmpl-Eew2ayvExaW7v8CP8U0kd4u0Vbqwr", response.id)
    }

    @Test
    fun textDeltasAreReportedInOrder() = runTest {
        val chunks = mutableListOf<ChatChunk>()
        client { stream(fixture("chat-stream-text.txt")) }.chatStream(request) { chunks += it }

        assertEquals(13, chunks.size)
        assertEquals(
            listOf("", "안", "녕하세요", "!", " 어떻게", " 도", "와", "드", "릴", "까요", "?"),
            chunks.mapNotNull { it.choices.firstOrNull()?.delta?.content },
        )
    }

    @Test
    fun usageChunkWithEmptyChoicesIsRead() = runTest {
        val response = client { stream(fixture("chat-stream-text.txt")) }.chatStream(request) {}

        assertEquals(54, response.usage.promptTokens)
        assertEquals(10, response.usage.completionTokens)
    }

    @Test
    fun assemblesToolCallsFromStream() = runTest {
        val response = client { stream(fixture("chat-stream-tool-calls.txt")) }.chatStream(request) {}

        val message = response.choices.single().message
        assertNull(message.content)
        assertEquals("tool_calls", response.choices.single().finishReason)
        val calls = message.toolCalls!!
        assertEquals(
            listOf("call_AGnndpeUFWBoJzZ0uL6VNYkv", "call_VLodlT3Yo7091bIvNx4uccBm", "call_XHLg0tj2ByypieOe1YzQt8UE"),
            calls.map { it.id },
        )
        assertEquals(listOf("get_shipment", "get_shipment", "get_shipment"), calls.map { it.function.name })
        assertEquals(listOf("function", "function", "function"), calls.map { it.type })
        assertEquals(
            listOf("ORD-0001", "ORD-0002", "ORD-0003"),
            calls.map { Json.parseToJsonElement(it.function.arguments).jsonObject["order_id"]!!.jsonPrimitive.content },
        )
        assertEquals(153, response.usage.promptTokens)
        assertEquals(73, response.usage.completionTokens)
    }

    @Test
    fun eventSplitAcrossReadsGivesSameResult() = runTest {
        val text = fixture("chat-stream-tool-calls.txt")
        val whole = client { stream(text) }.chatStream(request) {}

        val bytes = text.encodeToByteArray()
        val pieces = (bytes.indices step 7).map { bytes.copyOfRange(it, minOf(it + 7, bytes.size)) }
        val split = client { respond(pieces(this@runTest, pieces), HttpStatusCode.OK, SSE_HEADERS) }.chatStream(request) {}

        assertEquals(whole, split)
    }

    @Test
    fun multibyteCharacterSplitAcrossReadsIsIntact() = runTest {
        val bytes = fixture("chat-stream-text.txt").encodeToByteArray()
        val at = bytes.indexOfSequence("안".encodeToByteArray()) + 1
        val pieces = listOf(bytes.copyOfRange(0, at), bytes.copyOfRange(at, bytes.size))

        val response = client { respond(pieces(this@runTest, pieces), HttpStatusCode.OK, SSE_HEADERS) }.chatStream(request) {}

        assertEquals("안녕하세요! 어떻게 도와드릴까요?", response.choices.single().message.content)
    }

    @Test
    fun errorStatusOnStreamBecomesChatApiException() = runTest {
        val error = assertFailsWith<ChatApiException> {
            client { respond(fixture("error-401.json"), HttpStatusCode.Unauthorized, JSON_HEADERS) }.chatStream(request) {}
        }

        assertEquals(401, error.status)
        assertEquals("invalid_api_key", error.error!!.error.code)
    }

    private fun client(handler: MockRequestHandler) = OpenAiClient(HttpClient(MockEngine(handler)), "test-key")

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.stream(text: String) =
        respond(text, HttpStatusCode.OK, SSE_HEADERS)

    /** 조각마다 따로 쓰고 양보해서, 받는 쪽이 조각 경계에서 나뉘어 읽게 한다. */
    private fun pieces(scope: CoroutineScope, pieces: List<ByteArray>): ByteReadChannel = scope.writer {
        for (piece in pieces) {
            channel.writeFully(piece)
            channel.flush()
            yield()
        }
    }.channel

    private fun ByteArray.indexOfSequence(target: ByteArray): Int =
        (0..size - target.size).first { start -> target.indices.all { this[start + it] == target[it] } }

    private fun fixture(name: String) = javaClass.getResource("/openai/$name")!!.readText()

    private companion object {
        val SSE_HEADERS = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
