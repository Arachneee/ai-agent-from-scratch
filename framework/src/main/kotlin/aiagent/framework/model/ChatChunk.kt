package aiagent.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 스트리밍 응답의 `data:` 줄 하나에 실린 조각이다.
 * 글자와 툴 호출 인자는 [Delta]에 앞 조각에 이어 붙일 차이만 실린다. 사용량은 `choices`가 빈 마지막 조각에만 실린다.
 */
@Serializable
data class ChatChunk(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: ChatResponse.Usage?,
    @SerialName("service_tier")
    val serviceTier: String,
    @SerialName("system_fingerprint")
    val systemFingerprint: String,
) {
    @Serializable
    data class Choice(
        val index: Int,
        val delta: Delta,
        @SerialName("finish_reason")
        val finishReason: String?,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCallDelta>? = null,
    )

    /** 툴 호출 하나의 조각이다. [id], [type], [FunctionDelta.name]은 그 호출의 첫 조각에만 온다. */
    @Serializable
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionDelta,
    )

    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String,
    )
}
