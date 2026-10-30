package aiagent.shop.scenario

import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.ShipmentStatus
import aiagent.shop.fault.FaultConfig
import aiagent.shop.fault.ScheduledMutation
import aiagent.shop.fault.ServiceFaults
import aiagent.shop.fault.ServiceName

object Scenarios {
    private const val ORDER_ID = "ORD-0001"

    val all: List<Scenario> = listOf(
        Scenario(
            id = "order-status-basic",
            faults = FaultConfig(),
            messages = listOf("$ORDER_ID 주문 상태 알려 주세요"),
            check = { world, turns -> turns.last().reply.contains(world.state.orders.getValue(ORDER_ID).status.label()) },
        ),
        Scenario(
            id = "order-status-unavailable",
            faults = FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(errorRate = 1.0))),
            messages = listOf("$ORDER_ID 주문 상태 알려 주세요"),
            check = { world, turns -> turns.last().reply.contains(world.state.orders.getValue(ORDER_ID).status.label()) },
        ),
        Scenario(
            id = "refund-after-shipping",
            faults = FaultConfig(
                mutations = listOf(
                    ScheduledMutation(1, "$ORDER_ID 배송 출발") {
                        it.putOrder(it.orders.getValue(ORDER_ID).copy(status = OrderStatus.SHIPPED))
                        it.putShipment(it.shipments.getValue(ORDER_ID).copy(status = ShipmentStatus.IN_TRANSIT))
                    },
                ),
            ),
            messages = listOf("$ORDER_ID 환불해 주세요"),
            check = { _, turns -> turns.last().reply.contains("환불") },
        ),
    )
}
