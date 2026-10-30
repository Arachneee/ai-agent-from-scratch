package aiagent.shop.fault

import aiagent.shop.data.ShopDataGenerator
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.ShopState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FaultInjectorTest {
    private fun state(): ShopState = ShopDataGenerator(42).generate()

    private fun injector(
        latencyMs: LongRange = 0L..0L,
        errorRate: Double = 0.0,
        rateLimitEvery: Int? = null,
    ) = FaultInjector(7, FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(latencyMs, errorRate, rateLimitEvery))), state())

    private suspend fun outcomes(injector: FaultInjector, service: ServiceName, n: Int): List<String> =
        (1..n).map {
            try {
                injector.call(service, "op") { }
                "ok"
            } catch (e: ServiceException) {
                e::class.simpleName!!
            }
        }

    @Test
    fun sameSeedSameFailures() = runTest {
        val cfg = FaultConfig(mapOf(ServiceName.ORDER to ServiceFaults(errorRate = 0.3)))
        assertEquals(
            outcomes(FaultInjector(7, cfg, state()), ServiceName.ORDER, 50),
            outcomes(FaultInjector(7, cfg, state()), ServiceName.ORDER, 50),
        )
    }

    @Test
    fun extraCallsOnOneServiceDoNotShiftAnother() = runTest {
        val cfg = FaultConfig(ServiceName.entries.associateWith { ServiceFaults(errorRate = 0.3) })
        val plain = FaultInjector(7, cfg, state())
        val noisy = FaultInjector(7, cfg, state())
        outcomes(noisy, ServiceName.INVENTORY, 13)
        assertEquals(outcomes(plain, ServiceName.ORDER, 30), outcomes(noisy, ServiceName.ORDER, 30))
    }

    @Test
    fun errorRateBoundaries() = runTest {
        assertTrue(outcomes(injector(errorRate = 0.0), ServiceName.ORDER, 100).all { it == "ok" })
        assertTrue(outcomes(injector(errorRate = 1.0), ServiceName.ORDER, 100).all { it == "Unavailable" })
    }

    @Test
    fun rateLimitEveryThirdCall() = runTest {
        assertEquals(
            listOf("ok", "ok", "RateLimited", "ok", "ok", "RateLimited"),
            outcomes(injector(rateLimitEvery = 3), ServiceName.ORDER, 6),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun latencyIsInjectedWithinRange() = runTest {
        val inj = injector(latencyMs = 100L..300L)
        outcomes(inj, ServiceName.ORDER, 10)
        assertTrue(inj.injectedDelayMs in 1000L..3000L)
        assertEquals(inj.injectedDelayMs, testScheduler.currentTime)
    }

    @Test
    fun mutationFiresOnceBeforeTheNthCall() = runTest {
        val s = state()
        val inj = FaultInjector(
            7,
            FaultConfig(
                mutations = listOf(
                    ScheduledMutation(2, "ORD-0001 배송 출발") {
                        it.putOrder(it.orders.getValue("ORD-0001").copy(status = OrderStatus.SHIPPED))
                    },
                ),
            ),
            s,
        )
        s.putOrder(s.orders.getValue("ORD-0001").copy(status = OrderStatus.PAID))
        inj.call(ServiceName.ORDER, "getOrder") { }
        assertNotEquals(OrderStatus.SHIPPED, s.orders.getValue("ORD-0001").status)
        inj.call(ServiceName.ORDER, "getOrder") { }
        repeat(5) { inj.call(ServiceName.ORDER, "getOrder") { } }
        assertEquals(OrderStatus.SHIPPED, s.orders.getValue("ORD-0001").status)
        assertEquals(listOf("ORD-0001 배송 출발"), inj.firedMutations)
    }

    @Test
    fun invalidConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { ServiceFaults(errorRate = 1.5) }
        assertFailsWith<IllegalArgumentException> { ServiceFaults(rateLimitEvery = 0) }
    }
}
