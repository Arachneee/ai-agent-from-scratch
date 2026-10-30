package aiagent.shop.service

import aiagent.shop.domain.ShopState
import aiagent.shop.domain.Stock
import aiagent.shop.fault.FaultInjector
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceName

class InventoryService(private val state: ShopState, private val injector: FaultInjector) {
    suspend fun getStock(sku: String): Stock = injector.call(ServiceName.INVENTORY, "getStock") { stale ->
        val current = state.stocks[sku] ?: throw ServiceException.NotFound("상품", sku)
        if (stale) state.previousStock(sku) ?: current else current
    }
}
