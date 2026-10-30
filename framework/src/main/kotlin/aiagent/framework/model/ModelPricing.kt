package aiagent.framework.model

/**
 * 모델별 토큰 가격으로 호출 비용을 추정한다.
 * 가격은 2026-10-30에 OpenAI 가격표의 Standard 등급에서 확인한 100만 토큰당 달러 값이다.
 */
object ModelPricing {
    const val DEFAULT_MODEL = "gpt-4o-mini"

    /** 원화 표시에만 쓰는 대략적인 환율이다. */
    const val KRW_PER_USD = 1_400.0

    private val PRICES = mapOf(
        "gpt-4o-mini" to Price(inputPerMillion = 0.15, outputPerMillion = 0.60),
        "gpt-4.1-mini" to Price(inputPerMillion = 0.40, outputPerMillion = 1.60),
    )

    fun costUsd(model: String, inputTokens: Int, outputTokens: Int): Double? {
        val price = PRICES[model] ?: return null
        return (inputTokens * price.inputPerMillion + outputTokens * price.outputPerMillion) / 1_000_000
    }

    private data class Price(val inputPerMillion: Double, val outputPerMillion: Double)
}
