package aiagent.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<MessageRequest>,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val tools: List<Tool>? = null,
) {
    @Serializable
    data class MessageRequest(
        val role: String,
        val content: String?,
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ChatResponse.ToolCall>? = null,
    )

    @Serializable
    data class Tool(
        val type: String,
        val function: Function,
    )

    @Serializable
    data class Function(
        val name: String,
        val description: String,
        val parameters: Parameters,
    )

    @Serializable
    data class Parameters(
        val type: String,
        val properties: Map<String, Property>,
        val required: List<String>,
    )

    /** 인자 하나의 JSON 스키마다. 리스트, 중첩 객체, Map을 담기 위해 자기 자신을 품는다. */
    @Serializable
    data class Property(
        val type: String,
        val description: String? = null,
        val enum: List<String>? = null,
        val items: Property? = null,
        val properties: Map<String, Property>? = null,
        val required: List<String>? = null,
        val additionalProperties: Property? = null,
    )
}
