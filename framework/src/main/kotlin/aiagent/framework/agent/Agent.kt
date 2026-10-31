package aiagent.framework.agent

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.tool.ToolCatalog
import kotlinx.coroutines.CancellationException

/**
 * 한 턴마다 읽는 모델 호출 설정이다. [toolNames]가 비어 있으면 요청에 툴 정의를 싣지 않는다.
 * [maxCallsPerTool]은 한 턴 안에서 툴 하나를 실행할 수 있는 횟수이고, null이면 상한을 두지 않는다.
 */
data class AgentSettings(
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolNames: List<String> = emptyList(),
    val maxCallsPerTool: Int? = 3,
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
 * 툴 호출 응답에 함께 온 문장은 툴을 실행하기 전에 `onProgress`로 바로 알린다.
 * 한 턴 안에서 상한만큼 실행한 툴은 다음 요청의 툴 목록에서 빼고, 그 뒤의 호출은 실행하지 않고 오류 문장을 돌려보낸다.
 * 모든 툴이 빠지면 툴 정의 없이 요청하므로 모델은 글로 답해야 한다.
 */
class Agent(
    private val client: ChatClient,
    private val catalog: ToolCatalog,
    private val settings: () -> AgentSettings,
    private val systemPrompt: String,
) {
    private val history = mutableListOf<ChatRequest.MessageRequest>()

    suspend fun run(message: String, onProgress: (String) -> Unit = {}): TurnResult {
        val current = settings()
        val allTools = current.toolNames.takeIf { it.isNotEmpty() }?.let(catalog::definitions)
        val limit = current.maxCallsPerTool
        val callCounts = mutableMapOf<String, Int>()
        val canCall = { name: String -> limit == null || callCounts.getOrDefault(name, 0) < limit }
        val usages = mutableListOf<ChatResponse.Usage>()
        val executed = mutableListOf<ExecutedToolCall>()
        history += ChatRequest.MessageRequest("user", message)
        while (true) {
            val request = ChatRequest(
                model = current.model,
                messages = listOf(ChatRequest.MessageRequest("system", systemPrompt)) + history,
                temperature = current.temperature,
                maxTokens = current.maxTokens,
                tools = allTools?.filter { canCall(it.function.name) }?.takeIf { it.isNotEmpty() },
            )
            val response = client.chat(request).getOrThrow()
            usages += response.usage
            val choice = response.choices.first()
            val toolCalls = choice.message.toolCalls.orEmpty()
            if (toolCalls.isEmpty()) {
                val content = choice.message.content.orEmpty()
                history += ChatRequest.MessageRequest("assistant", content)
                return TurnResult(content.ifEmpty { "[빈 응답] finish_reason=${choice.finishReason}" }, usages, executed)
            }
            history += ChatRequest.MessageRequest("assistant", choice.message.content, toolCalls = toolCalls)
            choice.message.content?.takeIf { it.isNotEmpty() }?.let(onProgress)
            for (call in toolCalls) {
                val name = call.function.name
                val result = if (canCall(name)) {
                    callCounts[name] = callCounts.getOrDefault(name, 0) + 1
                    execute(call)
                } else {
                    "[툴 오류] ${name}는 이번 턴에 더 쓸 수 없습니다"
                }
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
