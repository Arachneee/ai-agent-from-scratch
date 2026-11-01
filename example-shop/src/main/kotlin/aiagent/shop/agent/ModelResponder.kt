package aiagent.shop.agent

import aiagent.framework.agent.Agent
import aiagent.framework.agent.AgentEvent
import aiagent.framework.agent.AgentSettings
import aiagent.framework.agent.CachedModelSummarizer
import aiagent.framework.agent.DropSummarizer
import aiagent.framework.agent.ExecutedToolCall
import aiagent.framework.agent.HistorySummarizer
import aiagent.framework.agent.ModelSummarizer
import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ModelPricing
import aiagent.shop.scenario.Responder

data class CallUsage(val inputTokens: Int, val cachedTokens: Int, val outputTokens: Int, val costUsd: Double?)

/**
 * 프레임워크의 [Agent]를 쇼핑몰의 [Responder]로 쓰게 한다. 대화 기록과 툴 호출 루프는 [Agent]가 맡는다.
 * 모델 API 오류는 답 대신 `[모델 오류]` 문장으로 보여 준다.
 * 글자 조각과 툴 실행 직전 알림은 [AgentEvent]로 `onEvent`에 턴이 끝나기 전에 넘긴다.
 */
class ModelResponder(
    client: ChatClient,
    tools: ShopTools,
    private val settings: () -> ModelSettings,
    systemPrompt: String = SYSTEM_PROMPT,
    summarizer: HistorySummarizer = DropSummarizer,
) : Responder {
    private val agent = Agent(client, tools.catalog, { settings().toAgentSettings() }, systemPrompt, summarizer)

    var lastUsages: List<CallUsage> = emptyList()
        private set

    var lastToolCalls: List<ExecutedToolCall> = emptyList()
        private set

    override suspend fun respond(message: String): String = respond(message) {}

    suspend fun respond(message: String, onEvent: (AgentEvent) -> Unit): String {
        lastUsages = emptyList()
        lastToolCalls = emptyList()
        val model = settings().model
        val result = try {
            agent.run(message, onEvent)
        } catch (e: ChatApiException) {
            return "[모델 오류] ${e.status} ${e.error?.error?.message ?: "오류 본문을 해석하지 못했습니다"}"
        }
        // 응답의 model은 날짜가 붙은 스냅숏 이름이라 가격표에 없으므로, 요청에 쓴 이름으로 비용을 찾는다.
        lastUsages = result.usages.map {
            val cached = it.promptTokensDetails.cachedTokens
            CallUsage(it.promptTokens, cached, it.completionTokens, ModelPricing.costUsd(model, it.promptTokens, cached, it.completionTokens))
        }
        lastToolCalls = result.toolCalls
        return result.answer
    }

    fun reset() = agent.reset()

    private fun ModelSettings.toAgentSettings() =
        AgentSettings(
            model, temperature, maxTokens, if (toolsEnabled) ShopTools.NAMES else emptyList(),
            historyTokenLimit = historyTokenLimit,
        )

    companion object {
        const val SYSTEM_PROMPT =
            "너는 가상 쇼핑몰의 고객지원 상담원이다. 고객의 주문 조회, 배송, 교환, 환불 문의에 한국어로 친절하고 간결하게 답한다."

        private const val SUMMARY_FOCUS = " 고객이 말한 주문번호와 각 주문의 상태는 반드시 포함하라."

        /** 기록을 요약할 때 쓰는 지시문이다. 프레임워크의 기본 지시문에 주문번호와 상태를 반드시 남기라는 말을 더한다. */
        const val SUMMARY_INSTRUCTION = ModelSummarizer.DEFAULT_INSTRUCTION + SUMMARY_FOCUS

        /** [CachedModelSummarizer]에 쓰는 지시문이다. */
        const val CACHED_SUMMARY_INSTRUCTION = CachedModelSummarizer.DEFAULT_INSTRUCTION + SUMMARY_FOCUS
    }
}
