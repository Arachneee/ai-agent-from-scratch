package aiagent.framework.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatStreamAssemblerTest {
    @Test
    fun interleavedToolCallChunksAreAssembledByIndex() {
        val assembler = ChatStreamAssembler()
        assembler.add(chunk(ChatChunk.Delta(role = "assistant")))
        assembler.add(chunk(toolDelta(0, "call_a", "get_order", "")))
        assembler.add(chunk(toolDelta(1, "call_b", "get_shipment", "")))
        assembler.add(chunk(toolDelta(0, arguments = "{\"order_id\":")))
        assembler.add(chunk(toolDelta(1, arguments = "{\"order_id\":\"ORD-0002\"}")))
        assembler.add(chunk(toolDelta(0, arguments = "\"ORD-0001\"}")))
        assembler.add(chunk(ChatChunk.Delta(), finishReason = "tool_calls"))
        assembler.add(usageChunk())

        val calls = assembler.result().choices.single().message.toolCalls!!

        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("""{"order_id":"ORD-0001"}""", """{"order_id":"ORD-0002"}"""), calls.map { it.function.arguments })
    }

    @Test
    fun streamWithoutUsageThrows() {
        val assembler = ChatStreamAssembler()
        assembler.add(chunk(ChatChunk.Delta(role = "assistant", content = "안녕")))
        assembler.add(chunk(ChatChunk.Delta(), finishReason = "stop"))

        assertFailsWith<IllegalStateException> { assembler.result() }
    }

    private fun toolDelta(index: Int, id: String? = null, name: String? = null, arguments: String) = ChatChunk.Delta(
        toolCalls = listOf(
            ChatChunk.ToolCallDelta(index, id, if (id != null) "function" else null, ChatChunk.FunctionDelta(name, arguments)),
        ),
    )

    private fun chunk(delta: ChatChunk.Delta, finishReason: String? = null) =
        ChatChunk("chatcmpl-test", 0, "gpt-4o-mini", listOf(ChatChunk.Choice(0, delta, finishReason)), null, "default", "fp_test")

    private fun usageChunk() = ChatChunk(
        "chatcmpl-test", 0, "gpt-4o-mini", emptyList(),
        ChatResponse.Usage(10, 5, 15, ChatResponse.PromptTokensDetails(0, 0), ChatResponse.CompletionTokensDetails(0, 0, 0, 0)),
        "default", "fp_test",
    )
}
