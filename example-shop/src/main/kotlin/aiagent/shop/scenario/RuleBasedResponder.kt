package aiagent.shop.scenario

import aiagent.shop.ShopWorld
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.ShipmentStatus

/**
 * 모델 없이 정규식으로 주문 상태만 답하는 기준 응답기다.
 * 오류를 처리하지 않으며, 이후 단계에서 LLM 에이전트와 비교하는 기준선으로 쓴다.
 */
class RuleBasedResponder(private val world: ShopWorld) : Responder {
    override suspend fun respond(message: String): String {
        val orderId = ORDER_ID.find(message)?.value ?: return "주문번호를 알려 주세요."
        val order = world.orders.getOrder(orderId)
        val shipment = world.shipping.getShipment(orderId)
        return "주문 ${order.id}의 상태는 ${order.status.label()}이고, 배송은 ${shipment.status.label()}입니다."
    }

    private companion object {
        val ORDER_ID = Regex("""ORD-\d{4}""")
    }
}

fun OrderStatus.label(): String = when (this) {
    OrderStatus.PAID -> "결제 완료"
    OrderStatus.PREPARING -> "상품 준비 중"
    OrderStatus.SHIPPED -> "배송 출발"
    OrderStatus.DELIVERED -> "배송 완료"
    OrderStatus.CANCELLED -> "취소"
    OrderStatus.EXCHANGE_REQUESTED -> "교환 요청"
}

fun ShipmentStatus.label(): String = when (this) {
    ShipmentStatus.READY -> "출고 대기"
    ShipmentStatus.IN_TRANSIT -> "배송 중"
    ShipmentStatus.DELAYED -> "배송 지연"
    ShipmentStatus.DELIVERED -> "배송 완료"
}
