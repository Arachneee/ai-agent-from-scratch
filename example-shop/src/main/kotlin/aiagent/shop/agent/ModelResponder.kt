package aiagent.shop.agent

import aiagent.framework.agent.Agent
import aiagent.framework.agent.AgentEvent
import aiagent.framework.agent.AgentSettings
import aiagent.framework.agent.ExecutedToolCall
import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ModelPricing
import aiagent.shop.scenario.Responder

data class CallUsage(val inputTokens: Int, val outputTokens: Int, val costUsd: Double?)

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
) : Responder {
    private val agent = Agent(client, tools.catalog, { settings().toAgentSettings() }, systemPrompt)

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
            CallUsage(it.promptTokens, it.completionTokens, ModelPricing.costUsd(model, it.promptTokens, it.completionTokens))
        }
        lastToolCalls = result.toolCalls
        return result.answer
    }

    fun reset() = agent.reset()

    private fun ModelSettings.toAgentSettings() =
        AgentSettings(model, temperature, maxTokens, if (toolsEnabled) ShopTools.NAMES else emptyList())

    companion object {
        const val SYSTEM_PROMPT =
            "너는 가상 쇼핑몰의 고객지원 상담원이다. 고객의 주문 조회, 배송, 교환, 환불 문의에 한국어로 친절하고 간결하게 답한다."
    }
}
