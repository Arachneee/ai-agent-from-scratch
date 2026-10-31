package aiagent.framework.model

/**
 * 스트림 조각을 받은 순서대로 [add]로 모아 일반 응답과 같은 [ChatResponse]로 조립한다.
 * 글자는 이어 붙이고, 툴 호출은 조각의 `index`별로 묶어 첫 조각의 `id`, `type`, 이름을 기억하고 인자를 이어 붙인다.
 * 조각에 없는 `object`는 `chat.completion`으로, `annotations`는 빈 목록으로 채운다.
 */
class ChatStreamAssembler {
    private var first: ChatChunk? = null
    private var role: String? = null
    private var content: StringBuilder? = null
    private val toolCalls = sortedMapOf<Int, ToolCallParts>()
    private var finishReason: String? = null
    private var usage: ChatResponse.Usage? = null

    fun add(chunk: ChatChunk) {
        if (first == null) first = chunk
        chunk.usage?.let { usage = it }
        for (choice in chunk.choices) {
            choice.delta.role?.let { role = it }
            choice.delta.content?.let { (content ?: StringBuilder().also { content = it }).append(it) }
            choice.delta.toolCalls?.forEach { delta ->
                val parts = toolCalls.getOrPut(delta.index) { ToolCallParts() }
                delta.id?.let { parts.id = it }
                delta.type?.let { parts.type = it }
                delta.function.name?.let { parts.name = it }
                parts.arguments.append(delta.function.arguments)
            }
            choice.finishReason?.let { finishReason = it }
        }
    }

    /** 사용량 조각이나 `finish_reason`을 받지 못했으면 스트림이 끝까지 오지 않은 것이므로 [IllegalStateException]을 던진다. */
    fun result(): ChatResponse {
        val first = checkNotNull(first) { "스트림 조각을 하나도 받지 못했습니다" }
        val usage = checkNotNull(usage) { "스트림에서 사용량 조각을 받지 못했습니다" }
        val finishReason = checkNotNull(finishReason) { "스트림에서 finish_reason을 받지 못했습니다" }
        val message = ChatResponse.MessageResponse(
            role = checkNotNull(role) { "스트림에서 role을 받지 못했습니다" },
            content = content?.toString(),
            refusal = null,
            annotations = emptyList(),
            toolCalls = toolCalls.values.map { it.toToolCall() }.takeIf { it.isNotEmpty() },
        )
        return ChatResponse(
            id = first.id,
            objectType = "chat.completion",
            created = first.created,
            model = first.model,
            choices = listOf(ChatResponse.Choice(0, message, null, finishReason)),
            usage = usage,
            serviceTier = first.serviceTier,
            systemFingerprint = first.systemFingerprint,
        )
    }

    private class ToolCallParts {
        var id: String? = null
        var type: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toToolCall() = ChatResponse.ToolCall(
            id = checkNotNull(id) { "툴 호출의 id를 받지 못했습니다" },
            type = checkNotNull(type) { "툴 호출의 type을 받지 못했습니다" },
            function = ChatResponse.FunctionCall(checkNotNull(name) { "툴 호출의 이름을 받지 못했습니다" }, arguments.toString()),
        )
    }
}
