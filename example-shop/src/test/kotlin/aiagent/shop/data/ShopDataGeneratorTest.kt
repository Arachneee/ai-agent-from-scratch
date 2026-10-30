package aiagent.shop.data

import aiagent.shop.domain.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShopDataGeneratorTest {
    @Test
    fun sameSeedProducesSameState() {
        val a = ShopDataGenerator(42).generate()
        val b = ShopDataGenerator(42).generate()
        assertEquals(a.orders, b.orders)
        assertEquals(a.payments, b.payments)
        assertEquals(a.shipments, b.shipments)
    }

    @Test
    fun differentSeedProducesDifferentState() {
        assertNotEquals(ShopDataGenerator(1).generate().orders, ShopDataGenerator(2).generate().orders)
    }

    @Test
    fun generatedDataIsConsistent() {
        val s = ShopDataGenerator(42).generate()
        assertTrue(s.orders.containsKey("ORD-0001"))
        s.orders.values.forEach { o ->
            assertEquals(o.items.sumOf { it.unitPrice * it.quantity }, s.payments.getValue(o.id).paidAmount)
            assertTrue(s.shipments.containsKey(o.id))
        }
    }

    @Test
    fun previousKeepsOneVersion() {
        val s = ShopDataGenerator(42).generate()
        val before = s.orders.getValue("ORD-0001")
        s.putOrder(before.copy(status = OrderStatus.CANCELLED))
        assertEquals(before, s.previousOrder("ORD-0001"))
    }
}
