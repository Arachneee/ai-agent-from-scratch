package aiagent.framework.tool

import aiagent.framework.model.ChatRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 툴을 이름으로 등록해 두고, 요청에 실을 정의를 꺼내거나 툴을 실행한다.
 * 정의의 인자 스키마는 입력 타입에서 만들고, 실행할 때 인자 문자열을 입력 타입으로 풀고 결과를 JSON 문자열로 바꾼다.
 */
class ToolCatalog {
    private val tools = mutableMapOf<String, Pair<ChatRequest.Tool, suspend (String) -> String>>()

    inline fun <reified I, reified O> register(name: String, description: String, noinline execute: suspend (I) -> O) =
        register(name, description, serializer<I>(), serializer<O>(), execute)

    @PublishedApi
    internal fun <I, O> register(
        name: String,
        description: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        execute: suspend (I) -> O,
    ) {
        val definition = ChatRequest.Tool(
            type = "function",
            function = ChatRequest.Function(name, description, toolParameters(input.descriptor)),
        )
        tools[name] = definition to { arguments -> json.encodeToString(output, execute(json.decodeFromString(input, arguments))) }
    }

    fun definitions(names: List<String>): List<ChatRequest.Tool> = names.map { find(it).first }

    suspend fun execute(name: String, arguments: String): String = find(name).second(arguments)

    private fun find(name: String) = tools[name] ?: throw IllegalArgumentException("등록되지 않은 툴: $name")

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
