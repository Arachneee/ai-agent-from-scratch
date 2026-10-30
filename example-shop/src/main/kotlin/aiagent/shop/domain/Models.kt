package aiagent.shop.domain

import java.time.Instant

data class Customer(val id: String, val name: String)

data class Product(val sku: String, val name: String, val price: Long)

data class OrderItem(val sku: String, val quantity: Int, val unitPrice: Long)

data class Order(
    val id: String,
    val customerId: String,
    val items: List<OrderItem>,
    val status: OrderStatus,
    val orderedAt: Instant,
)

enum class OrderStatus { PAID, PREPARING, SHIPPED, DELIVERED, CANCELLED, EXCHANGE_REQUESTED }

data class Payment(val orderId: String, val paidAmount: Long, val refundedAmount: Long)

data class Stock(val sku: String, val quantity: Int)

data class Shipment(val orderId: String, val status: ShipmentStatus, val eta: Instant)

enum class ShipmentStatus { READY, IN_TRANSIT, DELAYED, DELIVERED }
