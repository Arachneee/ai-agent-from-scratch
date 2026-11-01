package aiagent.framework.agent

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse

/** 잘라 낸 기록을 다음 요청에 남길 요약으로 바꾼다. [Agent]가 언제 어디서 자를지 정하고, 이 전략은 잘라 낸 것을 어떻게 남길지만 정한다. */
fun interface HistorySummarizer {
    suspend fun summarize(previous: String?, dropped: List<ChatRequest.MessageRequest>): Summary
}

/** [usage]는 요약에 모델 요청을 썼을 때만 있다. 그 사용량은 요약한 턴의 비용으로 센다. */
data class Summary(val text: String?, val usage: ChatResponse.Usage?)

/** 요약하지 않고 버린다. 이전 요약도 지운다. */
object DropSummarizer : HistorySummarizer {
    override suspend fun summarize(previous: String?, dropped: List<ChatRequest.MessageRequest>) = Summary(null, null)
}

/** 이전 요약과 잘라 낸 기록을 글로 바꿔 모델에게 보내고, 받은 답을 새 요약으로 쓴다. 툴 정의는 싣지 않는다. */
class ModelSummarizer(
    private val client: ChatClient,
    private val model: String,
    private val instruction: String = DEFAULT_INSTRUCTION,
) : HistorySummarizer {
    override suspend fun summarize(previous: String?, dropped: List<ChatRequest.MessageRequest>): Summary {
        val transcript = buildString {
            previous?.let { appendLine("이전 요약: $it") }
            dropped.forEach { appendLine(render(it)) }
        }
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatRequest.MessageRequest("system", instruction), ChatRequest.MessageRequest("user", transcript)),
        )
        val response = client.chat(request).getOrThrow()
        return Summary(response.choices.first().message.content, response.usage)
    }

    private fun render(message: ChatRequest.MessageRequest): String = when {
        message.role == "user" -> "사용자: ${message.content}"
        message.role == "tool" -> "툴 결과: ${message.content}"
        message.toolCalls != null -> message.toolCalls.joinToString("\n") { "툴 호출: ${it.function.name} ${it.function.arguments}" }
        else -> "어시스턴트: ${message.content}"
    }

    companion object {
        const val DEFAULT_INSTRUCTION =
            "다음은 지금까지의 대화 기록이다. 이후 대화에 필요한 사실, 곧 상대가 말한 식별자, 조회한 결과, 진행 중인 요청을 빠짐없이 짧게 정리하라. " +
                "이전 요약이 있으면 그 내용도 이어서 함께 정리하라."
    }
}
