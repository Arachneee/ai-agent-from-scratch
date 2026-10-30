package aiagent.shop.domain

import kotlinx.serialization.Serializable
import java.time.Instant

data class Customer(val id: String, val name: String)

data class Product(val sku: String, val name: String, val price: Long)

@Serializable
data class OrderItem(val sku: String, val quantity: Int, val unitPrice: Long)

@Serializable
data class Order(
    val id: String,
    val customerId: String,
    val items: List<OrderItem>,
    val status: OrderStatus,
    @Serializable(with = InstantSerializer::class)
    val orderedAt: Instant,
)

@Serializable
enum class OrderStatus { PAID, PREPARING, SHIPPED, DELIVERED, CANCELLED, EXCHANGE_REQUESTED }

data class Payment(val orderId: String, val paidAmount: Long, val refundedAmount: Long)

data class Stock(val sku: String, val quantity: Int)

@Serializable
data class Shipment(
    val orderId: String,
    val status: ShipmentStatus,
    @Serializable(with = InstantSerializer::class)
    val eta: Instant,
)

@Serializable
enum class ShipmentStatus { READY, IN_TRANSIT, DELAYED, DELIVERED }
