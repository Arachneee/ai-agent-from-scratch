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
) {
    @Serializable
    data class MessageRequest(
        val role: String,
        val content: String,
    )
}
