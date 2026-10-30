package aiagent.shop.agent

import aiagent.framework.tool.Description
import aiagent.framework.tool.ToolCatalog
import aiagent.shop.ShopWorld
import aiagent.shop.domain.Order
import aiagent.shop.domain.Shipment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrderIdArgs(@SerialName("order_id") @Description("주문번호. 예: ORD-0001") val orderId: String)

/** 쇼핑몰의 주문 조회 툴과 배송 조회 툴을 카탈로그에 등록한다. */
class ShopTools(world: ShopWorld) {
    val catalog = ToolCatalog().apply {
        register<OrderIdArgs, Order>("get_order", "주문번호로 주문 상태를 조회한다.") { world.orders.getOrder(it.orderId) }
        register<OrderIdArgs, Shipment>("get_shipment", "주문번호로 배송 상태를 조회한다.") { world.shipping.getShipment(it.orderId) }
    }

    companion object {
        val NAMES = listOf("get_order", "get_shipment")
    }
}
