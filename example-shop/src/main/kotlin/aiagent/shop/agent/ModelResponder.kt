package aiagent.shop.agent

import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatClient
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ModelPricing
import aiagent.shop.scenario.Responder

data class CallUsage(val inputTokens: Int, val outputTokens: Int, val costUsd: Double?)

/**
 * 모델에게 대화를 보내 답을 받는 응답기다. 대화 기록은 성공한 턴만 쌓는다.
 * 주문 정보를 조회할 수단이 없으므로, 주문 상태를 물으면 모델이 지어낼 수 있다.
 */
class ModelResponder(
    private val client: ChatClient,
    private val settings: () -> ModelSettings,
    private val systemPrompt: String = SYSTEM_PROMPT,
) : Responder {
    private val history = mutableListOf<ChatRequest.MessageRequest>()

    var lastUsage: CallUsage? = null
        private set

    override suspend fun respond(message: String): String {
        val current = settings()
        val user = ChatRequest.MessageRequest("user", message)
        val request = ChatRequest(
            model = current.model,
            messages = listOf(ChatRequest.MessageRequest("system", systemPrompt)) + history + user,
            temperature = current.temperature,
            maxTokens = current.maxTokens,
            tools = if (current.toolsEnabled) ShopTools.definitions else null,
        )
        lastUsage = null
        val response = client.chat(request).getOrElse { return failureText(it) }

        val choice = response.choices.first()
        val content = choice.message.content.orEmpty()
        history += user
        history += ChatRequest.MessageRequest("assistant", content)
        val usage = response.usage
        // 응답의 model은 날짜가 붙은 스냅숏 이름이라 가격표에 없으므로, 요청에 쓴 이름으로 비용을 찾는다.
        lastUsage = CallUsage(
            usage.promptTokens,
            usage.completionTokens,
            ModelPricing.costUsd(current.model, usage.promptTokens, usage.completionTokens),
        )
        return content.ifEmpty { "[빈 응답] finish_reason=${choice.finishReason}" }
    }

    fun reset() = history.clear()

    private fun failureText(e: Throwable): String = when (e) {
        is ChatApiException -> "[모델 오류] ${e.status} ${e.error?.error?.message ?: "오류 본문을 해석하지 못했습니다"}"
        else -> "[모델 오류] ${e::class.simpleName}: ${e.message}"
    }

    companion object {
        const val SYSTEM_PROMPT =
            "너는 가상 쇼핑몰의 고객지원 상담원이다. 고객의 주문 조회, 배송, 교환, 환불 문의에 한국어로 친절하고 간결하게 답한다."
    }
}
