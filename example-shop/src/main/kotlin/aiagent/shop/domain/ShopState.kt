package aiagent.shop.domain

/**
 * 가짜 서비스 네 개가 공유하는 메모리 상태다.
 * 오래된 읽기 장애를 흉내 내기 위해 엔티티마다 덮어쓰기 직전의 값을 한 단계만 보관한다.
 */
class ShopState {
    val customers = mutableMapOf<String, Customer>()
    val products = mutableMapOf<String, Product>()
    val orders = mutableMapOf<String, Order>()
    val payments = mutableMapOf<String, Payment>()
    val stocks = mutableMapOf<String, Stock>()
    val shipments = mutableMapOf<String, Shipment>()

    private val previousOrders = mutableMapOf<String, Order>()
    private val previousPayments = mutableMapOf<String, Payment>()
    private val previousStocks = mutableMapOf<String, Stock>()
    private val previousShipments = mutableMapOf<String, Shipment>()

    fun putOrder(order: Order) = put(orders, previousOrders, order.id, order)

    fun putPayment(payment: Payment) = put(payments, previousPayments, payment.orderId, payment)

    fun putStock(stock: Stock) = put(stocks, previousStocks, stock.sku, stock)

    fun putShipment(shipment: Shipment) = put(shipments, previousShipments, shipment.orderId, shipment)

    fun previousOrder(id: String): Order? = previousOrders[id]

    fun previousPayment(orderId: String): Payment? = previousPayments[orderId]

    fun previousStock(sku: String): Stock? = previousStocks[sku]

    fun previousShipment(orderId: String): Shipment? = previousShipments[orderId]

    private fun <T> put(current: MutableMap<String, T>, previous: MutableMap<String, T>, id: String, value: T) {
        current[id]?.let { previous[id] = it }
        current[id] = value
    }
}
