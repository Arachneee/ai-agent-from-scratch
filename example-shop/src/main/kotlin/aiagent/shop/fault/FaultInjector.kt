package aiagent.shop.fault

import aiagent.shop.domain.ShopState
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 모든 가짜 서비스 호출이 거치는 장애 주입기다.
 * 서비스마다 난수 흐름을 따로 써서, 한 서비스의 호출 수가 바뀌어도 다른 서비스의 장애 순서는 그대로 유지된다.
 */
class FaultInjector(seed: Long, private val config: FaultConfig, private val state: ShopState) {
    private val randoms = ServiceName.entries.associateWith { Random(seed * 31 + it.ordinal) }
    private val callCounts = ServiceName.entries.associateWith { 0 }.toMutableMap()
    private val pendingMutations = config.mutations.toMutableList()
    private val fired = mutableListOf<String>()

    var totalCalls: Int = 0
        private set

    var injectedDelayMs: Long = 0
        private set

    val firedMutations: List<String> get() = fired

    fun callCount(service: ServiceName): Int = callCounts.getValue(service)

    suspend fun <T> call(service: ServiceName, operation: String, block: suspend (stale: Boolean) -> T): T {
        totalCalls++
        pendingMutations.filter { it.afterTotalCalls == totalCalls }.forEach {
            it.apply(state)
            fired += it.description
            pendingMutations -= it
        }

        val count = callCounts.getValue(service) + 1
        callCounts[service] = count

        val faults = config.services[service] ?: ServiceFaults()
        val random = randoms.getValue(service)
        val latency = random.nextLong(faults.latencyMs.first, faults.latencyMs.last + 1)
        val errorRoll = random.nextDouble()
        val staleRoll = random.nextDouble()

        if (faults.rateLimitEvery != null && count % faults.rateLimitEvery == 0) {
            throw ServiceException.RateLimited(service, operation)
        }
        if (latency > 0) {
            delay(latency)
            injectedDelayMs += latency
        }
        if (errorRoll < faults.errorRate) {
            throw ServiceException.Unavailable(service, operation)
        }
        return block(staleRoll < faults.staleReadRate)
    }
}
