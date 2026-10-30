package aiagent.framework.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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
}
