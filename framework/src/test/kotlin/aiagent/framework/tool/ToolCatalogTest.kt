package aiagent.framework.tool

import aiagent.framework.model.ChatRequest.Property
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolCatalogTest {
    @Serializable
    data class OrderIdArgs(@SerialName("order_id") @Description("주문번호") val orderId: String)

    @Serializable
    data class Status(val orderId: String, val status: String)

    private val catalog = ToolCatalog().apply {
        register<OrderIdArgs, Status>("get_order", "주문 상태를 조회한다.") { Status(it.orderId, "PREPARING") }
    }

    @Test
    fun definitionIsBuiltFromNameDescriptionAndInputType() {
        val function = catalog.definitions(listOf("get_order")).single().function

        assertEquals("get_order", function.name)
        assertEquals("주문 상태를 조회한다.", function.description)
        assertEquals(mapOf("order_id" to Property("string", "주문번호")), function.parameters.properties)
        assertEquals(listOf("order_id"), function.parameters.required)
    }

    @Test
    fun executeDecodesArgumentsAndEncodesResult() = runTest {
        val result = catalog.execute("get_order", """{"order_id":"ORD-0001"}""")

        assertEquals("""{"orderId":"ORD-0001","status":"PREPARING"}""", result)
    }

    @Test
    fun parsesToolCallArgumentsString() = runTest {
        var received: String? = null
        val echo = ToolCatalog().apply {
            register<OrderIdArgs, String>("echo", "받은 주문번호를 돌려준다.") { received = it.orderId; it.orderId }
        }

        echo.execute("echo", """{"order_id":"주문 \"ORD-0001\""}""")

        assertEquals("주문 \"ORD-0001\"", received)
    }

    @Test
    fun unknownArgumentKeysAreIgnored() = runTest {
        val result = catalog.execute("get_order", """{"order_id":"ORD-0001","extra":1}""")

        assertEquals("""{"orderId":"ORD-0001","status":"PREPARING"}""", result)
    }

    @Test
    fun unknownToolNameThrows() = runTest {
        assertFailsWith<IllegalArgumentException> { catalog.execute("get_refund", "{}") }
        assertFailsWith<IllegalArgumentException> { catalog.definitions(listOf("get_refund")) }
    }
}
