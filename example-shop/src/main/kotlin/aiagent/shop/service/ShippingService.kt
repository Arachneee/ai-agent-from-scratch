package aiagent.shop.service

import aiagent.shop.domain.Shipment
import aiagent.shop.domain.ShopState
import aiagent.shop.fault.FaultInjector
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceName

class ShippingService(private val state: ShopState, private val injector: FaultInjector) {
    suspend fun getShipment(orderId: String): Shipment = injector.call(ServiceName.SHIPPING, "getShipment") { stale ->
        val current = state.shipments[orderId] ?: throw ServiceException.NotFound("배송", orderId)
        if (stale) state.previousShipment(orderId) ?: current else current
    }
}
