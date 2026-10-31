package aiagent.shop.agent

import aiagent.shop.ShopWorld
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopToolsTest {
    private val world = ShopWorld.create(42)
    private val tools = ShopTools(world)

    @Test
    fun getOrderReturnsSeed42Status() = runTest {
        val result = tools.catalog.execute("get_order", """{"order_id":"ORD-0001"}""")

        assertTrue("\"status\":\"PREPARING\"" in result, result)
    }

    @Test
    fun getShipmentReturnsSeed42Status() = runTest {
        val result = tools.catalog.execute("get_shipment", """{"order_id":"ORD-0001"}""")

        assertTrue("\"status\":\"READY\"" in result, result)
    }

    @Test
    fun getOrderIncludesProductNameAndStatusLabel() = runTest {
        val name = world.state.products.getValue("SKU-009").name

        val result = tools.catalog.execute("get_order", """{"order_id":"ORD-0001"}""")

        assertTrue("\"statusLabel\":\"상품 준비 중\"" in result, result)
        assertTrue("\"name\":\"$name\"" in result, result)
        assertFalse("customerId" in result, result)
    }

    @Test
    fun getShipmentIncludesStatusLabel() = runTest {
        val result = tools.catalog.execute("get_shipment", """{"order_id":"ORD-0001"}""")

        assertTrue("\"statusLabel\":\"출고 대기\"" in result, result)
    }

    @Test
    fun bothToolsAreRegisteredWithOrderIdArgument() {
        val definitions = tools.catalog.definitions(ShopTools.NAMES)

        assertEquals(listOf("get_order", "get_shipment"), definitions.map { it.function.name })
        definitions.forEach { assertEquals(listOf("order_id"), it.function.parameters.required) }
    }
}
