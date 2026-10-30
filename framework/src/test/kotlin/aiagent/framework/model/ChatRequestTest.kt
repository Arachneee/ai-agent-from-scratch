package aiagent.framework.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRequestTest {
    @Test
    fun toolDefinitionSerializesToOpenAiShape() {
        val tool = ChatRequest.Tool(
            type = "function",
            function = ChatRequest.Function(
                name = "lookup",
                description = "설명",
                parameters = ChatRequest.Parameters(
                    type = "object",
                    properties = mapOf("id" to ChatRequest.Property(type = "string", description = "아이디")),
                    required = listOf("id"),
                ),
            ),
        )
        val request = ChatRequest(model = "m", messages = emptyList(), tools = listOf(tool))

        val first = Json.parseToJsonElement(Json.encodeToString(ChatRequest.serializer(), request))
            .jsonObject["tools"]!!.jsonArray[0].jsonObject
        val function = first["function"]!!.jsonObject
        val parameters = function["parameters"]!!.jsonObject
        assertEquals("function", first["type"]!!.jsonPrimitive.content)
        assertEquals("lookup", function["name"]!!.jsonPrimitive.content)
        assertEquals("string", parameters["properties"]!!.jsonObject["id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("id"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
