package aiagent.shop.agent

/**
 * 채팅 중에 바꿀 수 있는 모델 호출 설정이다. [temperature]와 [maxTokens]가 null이면 요청에서 그 필드를 보내지 않는다.
 * [toolsEnabled]가 false이면 요청에 툴 정의를 싣지 않는다.
 */
data class ModelSettings(
    val model: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val showRaw: Boolean,
    val toolsEnabled: Boolean = true,
)
