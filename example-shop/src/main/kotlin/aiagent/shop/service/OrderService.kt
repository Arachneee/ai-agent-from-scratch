package aiagent.shop.service

import aiagent.shop.domain.Order
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.ShopState
import aiagent.shop.domain.Stock
import aiagent.shop.fault.FaultInjector
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceName

class OrderService(private val state: ShopState, private val injector: FaultInjector) {
    suspend fun getOrder(orderId: String): Order = injector.call(ServiceName.ORDER, "getOrder") { stale ->
        val current = state.orders[orderId] ?: throw ServiceException.NotFound("주문", orderId)
        if (stale) state.previousOrder(orderId) ?: current else current
    }

    suspend fun listOrders(customerId: String): List<Order> = injector.call(ServiceName.ORDER, "listOrders") { stale ->
        if (!state.customers.containsKey(customerId)) throw ServiceException.NotFound("고객", customerId)
        state.orders.values
            .filter { it.customerId == customerId }
            .map { if (stale) state.previousOrder(it.id) ?: it else it }
    }

    suspend fun requestExchange(orderId: String, sku: String): Order = injector.call(ServiceName.ORDER, "requestExchange") {
        val order = state.orders[orderId] ?: throw ServiceException.NotFound("주문", orderId)
        val stock = state.stocks[sku] ?: throw ServiceException.NotFound("상품", sku)
        if (stock.quantity < 1) throw ServiceException.Rejected("$sku 재고 없음")
        state.putStock(Stock(sku, stock.quantity - 1))
        order.copy(status = OrderStatus.EXCHANGE_REQUESTED).also(state::putOrder)
    }
}
