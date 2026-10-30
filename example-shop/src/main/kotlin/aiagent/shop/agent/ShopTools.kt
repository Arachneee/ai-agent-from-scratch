package aiagent.shop.agent

import aiagent.framework.model.ChatRequest

/** 모델에게 알려 주는 쇼핑몰 툴의 정의다. 두 툴 모두 주문번호 하나를 받는다. */
object ShopTools {
    val definitions: List<ChatRequest.Tool> = listOf(
        orderIdTool("get_order", "주문번호로 주문 상태를 조회한다."),
        orderIdTool("get_shipment", "주문번호로 배송 상태를 조회한다."),
    )

    private fun orderIdTool(name: String, description: String) = ChatRequest.Tool(
        type = "function",
        function = ChatRequest.Function(
            name = name,
            description = description,
            parameters = ChatRequest.Parameters(
                type = "object",
                properties = mapOf("order_id" to ChatRequest.Property(type = "string", description = "주문번호. 예: ORD-0001")),
                required = listOf("order_id"),
            ),
        ),
    )
}
