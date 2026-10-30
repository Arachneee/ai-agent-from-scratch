package aiagent.shop.service

import aiagent.shop.ShopWorld
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.Stock
import aiagent.shop.fault.FaultConfig
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceFaults
import aiagent.shop.fault.ServiceName
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServicesTest {
    @Test
    fun unknownOrderIsNotFound() = runTest {
        assertFailsWith<ServiceException.NotFound> { ShopWorld.create(42).orders.getOrder("ORD-9999") }
    }

    @Test
    fun refundRejectsInvalidAmounts() = runTest {
        val w = ShopWorld.create(42)
        val paid = w.payments.getPayment("ORD-0001").paidAmount
        assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", 0) }
        assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", paid + 1) }
        w.payments.refund("ORD-0001", paid)
        assertFailsWith<ServiceException.Rejected> { w.payments.refund("ORD-0001", 1) }
    }

    @Test
    fun refundDoesNotCheckShippingPolicy() = runTest {
        val w = ShopWorld.create(42)
        val shipped = w.state.orders.values.first { it.status == OrderStatus.SHIPPED }
        w.payments.refund(shipped.id, 1)
    }

    @Test
    fun exchangeNeedsStock() = runTest {
        val w = ShopWorld.create(42)
        val sku = w.state.stocks.keys.first()
        w.state.putStock(Stock(sku, 0))
        assertFailsWith<ServiceException.Rejected> { w.orders.requestExchange("ORD-0001", sku) }
    }

    @Test
    fun staleReadReturnsPreviousVersion() = runTest {
        val w = ShopWorld.create(42, FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(staleReadRate = 1.0))))
        val before = w.state.orders.getValue("ORD-0001")
        w.state.putOrder(before.copy(status = OrderStatus.CANCELLED))
        assertEquals(before, w.orders.getOrder("ORD-0001"))
    }

    @Test
    fun staleReadWithoutHistoryReturnsCurrent() = runTest {
        val w = ShopWorld.create(42, FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(staleReadRate = 1.0))))
        assertEquals(w.state.orders.getValue("ORD-0001"), w.orders.getOrder("ORD-0001"))
    }

    @Test
    fun everyCallGoesThroughInjector() = runTest {
        val w = ShopWorld.create(42)
        w.orders.getOrder("ORD-0001")
        w.shipping.getShipment("ORD-0001")
        assertEquals(2, w.injector.totalCalls)
    }
}
