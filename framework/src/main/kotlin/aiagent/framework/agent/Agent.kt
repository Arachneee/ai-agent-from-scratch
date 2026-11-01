package aiagent.framework.agent

import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ChatResponse
import aiagent.framework.tool.ToolCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 한 턴마다 읽는 모델 호출 설정이다. [toolNames]가 비어 있으면 요청에 툴 정의를 싣지 않는다.
 * [maxCallsPerTool]은 한 턴 안에서 툴 하나를 실행할 수 있는 횟수이고, null이면 상한을 두지 않는다.
 * [historyTokenLimit]은 직전 응답의 입력 토큰이 이 값을 넘으면 다음 턴을 시작할 때 기록을 줄이는 기준이고, null이면 줄이지 않는다.
 * 줄일 때는 최근 [keepRecentTurns]개의 지난 턴과 이번 턴의 사용자 메시지만 남긴다.
 */
data class AgentSettings(
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolNames: List<String> = emptyList(),
    val maxCallsPerTool: Int? = 3,
    val historyTokenLimit: Int? = null,
    val keepRecentTurns: Int = 4,
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
 * 응답은 스트리밍으로 받고, 글자 조각은 도착하는 대로 [AgentEvent.TextDelta]로, 툴은 실행하기 직전에 [AgentEvent.ToolCallStarted]로 `onEvent`에 알린다.
 * 한 응답에 담긴 툴 호출은 동시에 실행하고, 결과는 끝난 순서가 아니라 응답에 나온 순서대로 돌려보낸다.
 * 툴 호출 메시지와 결과 메시지는 결과가 모두 모인 뒤에 함께 기록하므로, 도중에 예외가 나도 짝 없는 툴 호출이 기록에 남지 않는다.
 * 한 턴 안에서 상한만큼 실행한 툴은 다음 요청의 툴 목록에서 빼고, 그 뒤의 호출은 실행하지 않고 오류 문장을 돌려보낸다.
 * 모든 툴이 빠지면 툴 정의 없이 요청하므로 모델은 글로 답해야 한다.
 * 기록은 사용자 메시지를 경계로 턴 단위로만 잘라서 툴 호출 메시지와 결과 메시지의 짝을 지킨다.
 * 잘라 낸 기록은 [summarizer]가 요약으로 바꾸고, 요약은 시스템 프롬프트 바로 뒤의 시스템 메시지로 보낸다.
 */
class Agent(
    private val client: ChatClient,
    private val catalog: ToolCatalog,
    private val settings: () -> AgentSettings,
    private val systemPrompt: String,
    private val summarizer: HistorySummarizer = DropSummarizer,
) {
    private val history = mutableListOf<ChatRequest.MessageRequest>()
    private var summary: String? = null
    private var lastPromptTokens = 0

    suspend fun run(message: String, onEvent: (AgentEvent) -> Unit = {}): TurnResult {
        val current = settings()
        val allTools = current.toolNames.takeIf { it.isNotEmpty() }?.let(catalog::definitions)
        val limit = current.maxCallsPerTool
        val callCounts = mutableMapOf<String, Int>()
        val canCall = { name: String -> limit == null || callCounts.getOrDefault(name, 0) < limit }
        val usages = mutableListOf<ChatResponse.Usage>()
        val executed = mutableListOf<ExecutedToolCall>()
        history += ChatRequest.MessageRequest("user", message)
        if (current.historyTokenLimit != null && lastPromptTokens > current.historyTokenLimit) {
            val conversation = request(current, allTools, history.dropLast(1))
            compact(current.keepRecentTurns, conversation)?.let { result ->
                result.usage?.let { usages += it }
                onEvent(AgentEvent.HistorySummarized(result.text, result.usage))
            }
        }
        while (true) {
            val request = request(current, allTools?.filter { canCall(it.function.name) }, history)
            val response = client.chatStream(request) { chunk ->
                chunk.choices.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }?.let { onEvent(AgentEvent.TextDelta(it)) }
            }
            usages += response.usage
            lastPromptTokens = response.usage.promptTokens
            val choice = response.choices.first()
            val toolCalls = choice.message.toolCalls.orEmpty()
            if (toolCalls.isEmpty()) {
                val content = choice.message.content.orEmpty()
                history += ChatRequest.MessageRequest("assistant", content)
                return TurnResult(content.ifEmpty { "[빈 응답] finish_reason=${choice.finishReason}" }, usages, executed)
            }
            val allowed = toolCalls.map { call ->
                val name = call.function.name
                canCall(name).also { if (it) callCounts[name] = callCounts.getOrDefault(name, 0) + 1 }
            }
            val running = toolCalls.filterIndexed { i, _ -> allowed[i] }
            running.forEach { onEvent(AgentEvent.ToolCallStarted(it.function.name, it.function.arguments)) }
            val results = coroutineScope { running.map { async { execute(it) } }.awaitAll() }.iterator()
            history += ChatRequest.MessageRequest("assistant", choice.message.content, toolCalls = toolCalls)
            toolCalls.forEachIndexed { i, call ->
                val result = if (allowed[i]) results.next() else "[툴 오류] ${call.function.name}는 이번 턴에 더 쓸 수 없습니다"
                executed += ExecutedToolCall(call.function.name, call.function.arguments, result)
                history += ChatRequest.MessageRequest("tool", result, toolCallId = call.id)
            }
        }
    }

    fun reset() {
        history.clear()
        summary = null
        lastPromptTokens = 0
    }

    private fun request(current: AgentSettings, tools: List<ChatRequest.Tool>?, messages: List<ChatRequest.MessageRequest>) = ChatRequest(
        model = current.model,
        messages = listOf(ChatRequest.MessageRequest("system", systemPrompt)) +
            listOfNotNull(summary?.let { ChatRequest.MessageRequest("system", "지금까지의 대화 요약: $it") }) +
            messages,
        temperature = current.temperature,
        maxTokens = current.maxTokens,
        tools = tools?.takeIf { it.isNotEmpty() },
    )

    /**
     * 이번 턴의 사용자 메시지 앞에서 최근 [keep]개의 지난 턴만 남기고 잘라 낸 것을 요약한다.
     * [conversation]은 자르기 전의 기록으로 만든 요청이다. 자를 턴이 없으면 null을 돌려준다.
     */
    private suspend fun compact(keep: Int, conversation: ChatRequest): Summary? {
        val pastTurnStarts = history.indices.filter { history[it].role == "user" }.dropLast(1)
        if (pastTurnStarts.size <= keep) return null
        val cut = if (keep == 0) history.lastIndex else pastTurnStarts[pastTurnStarts.size - keep]
        val dropped = history.subList(0, cut)
        val result = summarizer.summarize(SummaryContext(summary, dropped.toList(), conversation))
        summary = result.text
        dropped.clear()
        return result
    }

    private suspend fun execute(call: ChatResponse.ToolCall): String = try {
        catalog.execute(call.function.name, call.function.arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "[툴 오류] ${e::class.simpleName}: ${e.message}"
    }
}
