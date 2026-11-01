package aiagent.shop.agent

/**
 * 채팅 중에 바꿀 수 있는 모델 호출 설정이다. [temperature]와 [maxTokens]가 null이면 요청에서 그 필드를 보내지 않는다.
 * [toolsEnabled]가 false이면 요청에 툴 정의를 싣지 않는다.
 * [historyTokenLimit]이 null이 아니면 직전 응답의 입력 토큰이 이 값을 넘을 때 대화 기록을 줄인다.
 */
data class ModelSettings(
    val model: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val showRaw: Boolean,
    val toolsEnabled: Boolean = true,
    val historyTokenLimit: Int? = null,
)
