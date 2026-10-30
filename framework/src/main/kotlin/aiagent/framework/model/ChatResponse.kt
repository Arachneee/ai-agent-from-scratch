package aiagent.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatResponse(
    val id: String,
    @SerialName("object")
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    @SerialName("service_tier")
    val serviceTier: String,
    @SerialName("system_fingerprint")
    val systemFingerprint: String,
) {
    @Serializable
    data class Choice(
        val index: Int,
        val message: MessageResponse,
        val logprobs: JsonElement?,
        @SerialName("finish_reason")
        val finishReason: String,
    )

    @Serializable
    data class MessageResponse(
        val role: String,
        val content: String?,
        val refusal: String?,
        val annotations: List<JsonElement>,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
    )

    @Serializable
    data class ToolCall(
        val id: String,
        val type: String,
        val function: FunctionCall,
    )

    @Serializable
    data class FunctionCall(
        val name: String,
        val arguments: String,
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens")
        val promptTokens: Int,
        @SerialName("completion_tokens")
        val completionTokens: Int,
        @SerialName("total_tokens")
        val totalTokens: Int,
        @SerialName("prompt_tokens_details")
        val promptTokensDetails: PromptTokensDetails,
        @SerialName("completion_tokens_details")
        val completionTokensDetails: CompletionTokensDetails,
    )

    @Serializable
    data class PromptTokensDetails(
        @SerialName("cached_tokens")
        val cachedTokens: Int,
        @SerialName("audio_tokens")
        val audioTokens: Int,
    )

    @Serializable
    data class CompletionTokensDetails(
        @SerialName("reasoning_tokens")
        val reasoningTokens: Int,
        @SerialName("audio_tokens")
        val audioTokens: Int,
        @SerialName("accepted_prediction_tokens")
        val acceptedPredictionTokens: Int,
        @SerialName("rejected_prediction_tokens")
        val rejectedPredictionTokens: Int,
    )
}
