package aiagent.shop

import aiagent.shop.data.ShopDataGenerator
import aiagent.shop.domain.ShopState
import aiagent.shop.fault.FaultConfig
import aiagent.shop.fault.FaultInjector
import aiagent.shop.service.InventoryService
import aiagent.shop.service.OrderService
import aiagent.shop.service.PaymentService
import aiagent.shop.service.ShippingService

/** 한 번의 실험에 쓰는 상태, 장애 주입기, 가짜 서비스 네 개의 묶음이다. */
class ShopWorld(
    val state: ShopState,
    val injector: FaultInjector,
    val orders: OrderService,
    val payments: PaymentService,
    val inventory: InventoryService,
    val shipping: ShippingService,
) {
    companion object {
        fun create(seed: Long, faults: FaultConfig = FaultConfig()): ShopWorld {
            val state = ShopDataGenerator(seed).generate()
            val injector = FaultInjector(seed, faults, state)
            return ShopWorld(
                state,
                injector,
                OrderService(state, injector),
                PaymentService(state, injector),
                InventoryService(state, injector),
                ShippingService(state, injector),
            )
        }
    }
}
