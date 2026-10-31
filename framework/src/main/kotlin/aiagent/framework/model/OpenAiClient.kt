package aiagent.framework.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class OpenAiClient(
    httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
) : ChatClient {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = httpClient.config {
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun chat(request: ChatRequest): Result<ChatResponse> = runCatching {
        val response = httpClient.post("$baseUrl/v1/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()) }.getOrNull()
            throw ChatApiException(response.status.value, error)
        }
        response.body<ChatResponse>()
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * 스트리밍으로 요청하고, 서버 전송 이벤트의 `data:` 줄마다 [ChatChunk]로 풀어서 [onChunk]로 넘긴다.
     * 빈 줄은 건너뛰고 `data: [DONE]`에서 읽기를 멈춘다. 사용량을 받도록 `include_usage`를 켠다.
     * 스트림이 끝나면 받은 조각을 [ChatStreamAssembler]로 조립한 응답을 돌려준다.
     */
    suspend fun chatStream(request: ChatRequest, onChunk: (ChatChunk) -> Unit): ChatResponse {
        return httpClient.preparePost("$baseUrl/v1/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request.copy(stream = true, streamOptions = ChatRequest.StreamOptions(includeUsage = true)))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val error = runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()) }.getOrNull()
                throw ChatApiException(response.status.value, error)
            }
            val assembler = ChatStreamAssembler()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isEmpty()) continue
                val data = line.removePrefix("data: ")
                if (data == "[DONE]") break
                val chunk = json.decodeFromString<ChatChunk>(data)
                assembler.add(chunk)
                onChunk(chunk)
            }
            assembler.result()
        }
    }
}
