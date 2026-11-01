package aiagent.framework.agent

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse

/** 잘라 낸 기록을 다음 요청에 남길 요약으로 바꾼다. [Agent]가 언제 어디서 자를지 정하고, 이 전략은 잘라 낸 것을 어떻게 남길지만 정한다. */
fun interface HistorySummarizer {
    suspend fun summarize(context: SummaryContext): Summary
}

/**
 * [previous]는 이전 요약, [dropped]는 이번에 잘라 낸 메시지다.
 * [conversation]은 자르기 전의 기록으로 만든 요청이고, 이번 턴의 사용자 메시지는 들어 있지 않다. 앞부분이 직전 요청과 같다.
 */
data class SummaryContext(
    val previous: String?,
    val dropped: List<ChatRequest.MessageRequest>,
    val conversation: ChatRequest,
)

/** [usage]는 요약에 모델 요청을 썼을 때만 있다. 그 사용량은 요약한 턴의 비용으로 센다. */
data class Summary(val text: String?, val usage: ChatResponse.Usage?)

/** 요약하지 않고 버린다. 이전 요약도 지운다. */
object DropSummarizer : HistorySummarizer {
    override suspend fun summarize(context: SummaryContext) = Summary(null, null)
}

/** 이전 요약과 잘라 낸 기록을 글로 바꿔 모델에게 보내고, 받은 답을 새 요약으로 쓴다. 툴 정의는 싣지 않는다. */
class ModelSummarizer(
    private val client: ChatClient,
    private val model: String,
    private val instruction: String = DEFAULT_INSTRUCTION,
) : HistorySummarizer {
    override suspend fun summarize(context: SummaryContext): Summary {
        val transcript = buildString {
            context.previous?.let { appendLine("이전 요약: $it") }
            context.dropped.forEach { appendLine(render(it)) }
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

/**
 * 직전 상담 요청을 그대로 두고 끝에 요약 지시만 붙여 보낸다. 요청의 앞부분이 직전 요청과 같아서 자동 프롬프트 캐싱을 다시 쓸 수 있다.
 * 툴 정의를 빼면 앞부분이 달라지므로 그대로 싣고, 모델이 툴을 부르지 않도록 `tool_choice`를 `none`으로 보낸다.
 * 모델이 대화 전체를 보므로, 어디까지를 정리할지는 남기는 첫 사용자 메시지를 들어 지시문에 적는다.
 */
class CachedModelSummarizer(
    private val client: ChatClient,
    private val instruction: String = DEFAULT_INSTRUCTION,
) : HistorySummarizer {
    override suspend fun summarize(context: SummaryContext): Summary {
        val messages = context.conversation.messages
        val firstKept = messages.drop(messages.indexOf(context.dropped.last()) + 1).firstOrNull { it.role == "user" }
        val range = if (firstKept == null) {
            "위 대화 전체와"
        } else {
            "위 대화 가운데 처음부터 사용자가 \"${firstKept.content}\"라고 말하기 직전까지의 내용과"
        }
        val request = context.conversation.copy(
            messages = messages + ChatRequest.MessageRequest("user", "$range 지금까지의 대화 요약이 있다면 그 내용을 정리하라. $instruction"),
            toolChoice = "none",
        )
        val response = client.chat(request).getOrThrow()
        return Summary(response.choices.first().message.content, response.usage)
    }

    companion object {
        const val DEFAULT_INSTRUCTION = "이후 대화에 필요한 사실, 곧 상대가 말한 식별자, 조회한 결과, 진행 중인 요청을 빠짐없이 짧게 정리하라."
    }
}
