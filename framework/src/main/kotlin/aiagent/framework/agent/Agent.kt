package aiagent.framework.agent

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.tool.ToolCatalog
import kotlinx.coroutines.CancellationException

/** 한 턴마다 읽는 모델 호출 설정이다. [toolNames]가 비어 있으면 요청에 툴 정의를 싣지 않는다. */
data class AgentSettings(
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolNames: List<String> = emptyList(),
)

data class ExecutedToolCall(val name: String, val arguments: String, val result: String)

/** 한 턴의 결과다. [usages]는 이 턴에 보낸 요청마다 하나씩 담긴다. */
data class TurnResult(
    val answer: String,
    val usages: List<ChatResponse.Usage>,
    val toolCalls: List<ExecutedToolCall>,
)

/**
 * 대화 기록을 들고 모델과 툴로 한 턴을 처리한다.
 * 응답에 툴 호출이 있으면 실행하고 결과를 돌려보내기를 툴 호출이 없는 응답이 올 때까지 반복한다.
 * 기록에는 실패한 턴의 메시지도 남는다. 툴 실행이 예외로 끝나면 예외 문장을 툴 결과로 돌려보낸다.
 */
class Agent(
    private val client: ChatClient,
    private val catalog: ToolCatalog,
    private val settings: () -> AgentSettings,
    private val systemPrompt: String,
) {
    private val history = mutableListOf<ChatRequest.MessageRequest>()

    suspend fun run(message: String): TurnResult {
        val current = settings()
        val tools = current.toolNames.takeIf { it.isNotEmpty() }?.let(catalog::definitions)
        val usages = mutableListOf<ChatResponse.Usage>()
        val executed = mutableListOf<ExecutedToolCall>()
        history += ChatRequest.MessageRequest("user", message)
        while (true) {
            val request = ChatRequest(
                model = current.model,
                messages = listOf(ChatRequest.MessageRequest("system", systemPrompt)) + history,
                temperature = current.temperature,
                maxTokens = current.maxTokens,
                tools = tools,
            )
            val response = client.chat(request).getOrThrow()
            usages += response.usage
            val choice = response.choices.first()
            val calls = choice.message.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                val content = choice.message.content.orEmpty()
                history += ChatRequest.MessageRequest("assistant", content)
                return TurnResult(content.ifEmpty { "[빈 응답] finish_reason=${choice.finishReason}" }, usages, executed)
            }
            history += ChatRequest.MessageRequest("assistant", choice.message.content, toolCalls = calls)
            for (call in calls) {
                val result = execute(call)
                executed += ExecutedToolCall(call.function.name, call.function.arguments, result)
                history += ChatRequest.MessageRequest("tool", result, toolCallId = call.id)
            }
        }
    }

    fun reset() = history.clear()

    private suspend fun execute(call: ChatResponse.ToolCall): String = try {
        catalog.execute(call.function.name, call.function.arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "[툴 오류] ${e::class.simpleName}: ${e.message}"
    }
}
