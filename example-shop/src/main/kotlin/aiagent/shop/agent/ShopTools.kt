package aiagent.shop.agent

import aiagent.framework.tool.Description
import aiagent.framework.tool.ToolCatalog
import aiagent.shop.ShopWorld
import aiagent.shop.domain.InstantSerializer
import aiagent.shop.domain.Order
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.Shipment
import aiagent.shop.domain.ShipmentStatus
import aiagent.shop.scenario.label
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OrderIdArgs(@SerialName("order_id") @Description("주문번호. 예: ORD-0001") val orderId: String)

/** 모델에게 돌려주는 주문 조회 결과다. 상품 이름과 상태의 한국어 표현을 담고, 답에 쓰이지 않는 고객 ID는 뺀다. */
@Serializable
data class OrderView(
    val id: String,
    val items: List<Item>,
    val status: OrderStatus,
    val statusLabel: String,
    @Serializable(with = InstantSerializer::class)
    val orderedAt: Instant,
) {
    @Serializable
    data class Item(val sku: String, val name: String, val quantity: Int, val unitPrice: Long)
}

/** 모델에게 돌려주는 배송 조회 결과다. 상태의 한국어 표현을 함께 담는다. */
@Serializable
data class ShipmentView(
    val orderId: String,
    val status: ShipmentStatus,
    val statusLabel: String,
    @Serializable(with = InstantSerializer::class)
    val eta: Instant,
)

/** 쇼핑몰의 주문 조회 툴과 배송 조회 툴을 카탈로그에 등록한다. */
class ShopTools(private val world: ShopWorld) {
    val catalog = ToolCatalog().apply {
        register<OrderIdArgs, OrderView>("get_order", "주문번호로 주문 상태를 조회한다.") { world.orders.getOrder(it.orderId).toView() }
        register<OrderIdArgs, ShipmentView>("get_shipment", "주문번호로 배송 상태를 조회한다.") { world.shipping.getShipment(it.orderId).toView() }
    }

    private fun Order.toView() = OrderView(
        id = id,
        items = items.map { OrderView.Item(it.sku, world.state.products.getValue(it.sku).name, it.quantity, it.unitPrice) },
        status = status,
        statusLabel = status.label(),
        orderedAt = orderedAt,
    )

    private fun Shipment.toView() = ShipmentView(orderId, status, status.label(), eta)

    companion object {
        val NAMES = listOf("get_order", "get_shipment")
    }
}
