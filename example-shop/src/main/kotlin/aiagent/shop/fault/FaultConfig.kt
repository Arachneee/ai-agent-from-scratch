package aiagent.shop.fault

import aiagent.shop.domain.ShopState

enum class ServiceName { ORDER, PAYMENT, INVENTORY, SHIPPING }

data class ServiceFaults(
    val latencyMs: LongRange = 0L..0L,
    val errorRate: Double = 0.0,
    val rateLimitEvery: Int? = null,
    val staleReadRate: Double = 0.0,
) {
    init {
        require(!latencyMs.isEmpty() && latencyMs.first >= 0) { "latencyMs는 0 이상의 비어 있지 않은 범위여야 한다: $latencyMs" }
        require(errorRate in 0.0..1.0) { "errorRate는 0.0에서 1.0 사이여야 한다: $errorRate" }
        require(staleReadRate in 0.0..1.0) { "staleReadRate는 0.0에서 1.0 사이여야 한다: $staleReadRate" }
        require(rateLimitEvery == null || rateLimitEvery >= 1) { "rateLimitEvery는 1 이상이어야 한다: $rateLimitEvery" }
    }
}

/** 전체 서비스 호출 수가 [afterTotalCalls]에 도달한 호출 직전에 한 번만 상태를 바꾼다. */
class ScheduledMutation(val afterTotalCalls: Int, val description: String, val apply: (ShopState) -> Unit)

data class FaultConfig(
    val services: Map<ServiceName, ServiceFaults> = emptyMap(),
    val mutations: List<ScheduledMutation> = emptyList(),
)
