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
        val content: String,
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

    @Serializable
    data class Property(
        val type: String,
        val description: String,
    )
}
