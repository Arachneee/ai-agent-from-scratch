package aiagent.shop.data

import aiagent.shop.domain.Customer
import aiagent.shop.domain.Order
import aiagent.shop.domain.OrderItem
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.Payment
import aiagent.shop.domain.Product
import aiagent.shop.domain.Shipment
import aiagent.shop.domain.ShipmentStatus
import aiagent.shop.domain.ShopState
import aiagent.shop.domain.Stock
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.random.Random

/** 같은 시드면 항상 같은 가짜 쇼핑몰 상태를 만든다. */
class ShopDataGenerator(seed: Long) {
    private val random = Random(seed)

    fun generate(customerCount: Int = 20): ShopState {
        val state = ShopState()
        PRODUCTS.forEachIndexed { index, (name, price) ->
            val sku = "SKU-%03d".format(index + 1)
            state.products[sku] = Product(sku, name, price)
            state.stocks[sku] = Stock(sku, random.nextInt(0, 30))
        }
        val skus = state.products.keys.toList()
        var orderNumber = 0
        repeat(customerCount) { index ->
            val customer = Customer("CUS-%03d".format(index + 1), FAMILY_NAMES.random(random) + GIVEN_NAMES.random(random))
            state.customers[customer.id] = customer
            repeat(random.nextInt(1, 5)) {
                orderNumber++
                val items = skus.shuffled(random).take(random.nextInt(1, 4)).map { sku ->
                    OrderItem(sku, random.nextInt(1, 4), state.products.getValue(sku).price)
                }
                val orderedAt = BASE_TIME.minus(Duration.ofHours(random.nextLong(1, 24 * 14)))
                val order = Order("ORD-%04d".format(orderNumber), customer.id, items, ORDER_STATUSES.random(random), orderedAt)
                state.orders[order.id] = order
                state.payments[order.id] = Payment(order.id, items.sumOf { it.unitPrice * it.quantity }, 0)
                state.shipments[order.id] = Shipment(order.id, shipmentStatusOf(order.status), orderedAt.plus(Duration.ofDays(random.nextLong(1, 5))))
            }
        }
        return state
    }

    private fun shipmentStatusOf(status: OrderStatus): ShipmentStatus = when (status) {
        OrderStatus.PAID, OrderStatus.PREPARING -> ShipmentStatus.READY
        OrderStatus.SHIPPED -> if (random.nextInt(4) == 0) ShipmentStatus.DELAYED else ShipmentStatus.IN_TRANSIT
        OrderStatus.DELIVERED -> ShipmentStatus.DELIVERED
        else -> ShipmentStatus.READY
    }

    private companion object {
        val BASE_TIME = OffsetDateTime.parse("2026-01-01T00:00:00+09:00").toInstant()

        val ORDER_STATUSES = listOf(OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)

        val PRODUCTS = listOf(
            "무선 이어폰" to 89_000L,
            "보조 배터리" to 32_000L,
            "기계식 키보드" to 129_000L,
            "무선 마우스" to 45_000L,
            "스마트 워치" to 239_000L,
            "블루투스 스피커" to 67_000L,
            "휴대폰 케이스" to 15_000L,
            "고속 충전기" to 28_000L,
            "노트북 거치대" to 39_000L,
            "USB 허브" to 24_000L,
        )

        val FAMILY_NAMES = listOf("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임")

        val GIVEN_NAMES = listOf("민준", "서연", "도윤", "하은", "시우", "지유", "예준", "서아", "주원", "하린")
    }
}
