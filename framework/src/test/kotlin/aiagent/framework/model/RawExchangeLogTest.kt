package aiagent.framework.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawExchangeLogTest {
    private val request = ChatRequest("gpt-4.1-nano", listOf(ChatRequest.MessageRequest("user", "원문 확인")))

    @Test
    fun rawOutputMasksAuthorization() = runTest {
        val lines = mutableListOf<String>()
        OpenAiClient(clientWithLog(enabled = true, lines), apiKey = "sk-secret").chat(request)

        val out = lines.joinToString("\n")
        assertFalse("sk-secret" in out, out)
        assertTrue("Authorization: ***" in out, out)
        assertTrue("\"content\":\"원문 확인\"" in out, out)
        assertTrue("\"finish_reason\":\"stop\"" in out.replace(" ", ""), out)
    }

    @Test
    fun disabledLogEmitsNothing() = runTest {
        val lines = mutableListOf<String>()
        OpenAiClient(clientWithLog(enabled = false, lines), apiKey = "sk-secret").chat(request)

        assertTrue(lines.isEmpty(), lines.joinToString("\n"))
    }

    private fun clientWithLog(enabled: Boolean, lines: MutableList<String>) =
        HttpClient(MockEngine { respond(OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            rawExchangeLog(enabled = { enabled }, sink = { lines += it })
        }

    private companion object {
        val OK_BODY = RawExchangeLogTest::class.java.getResource("/openai/chat-completion-ok.json")!!.readText()
    }
}
