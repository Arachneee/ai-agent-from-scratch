package aiagent.shop.scenario

import aiagent.shop.ShopWorld
import aiagent.shop.domain.OrderStatus
import aiagent.shop.domain.ShipmentStatus
import aiagent.shop.fault.FaultConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioRunnerTest {
    @Test
    fun reportFormat() {
        val text = ScenarioRunner(42) { RuleBasedResponder(it) }.report(
            listOf(
                ScenarioResult("a", true, null, 2, 0),
                ScenarioResult("b", false, "Unavailable: ORDER.getOrder", 1, 0),
            ),
        )
        assertEquals(
            """
            시나리오 결과 (seed=42)
            [성공] a: 서비스 호출 2회, 주입 지연 0ms
            [실패] b: Unavailable: ORDER.getOrder, 서비스 호출 1회, 주입 지연 0ms
            합계: 2개 중 1개 성공 (50.0%)
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun baselineResultsOnBuiltInScenarios() = runTest {
        val results = ScenarioRunner(42) { RuleBasedResponder(it) }.run(Scenarios.all)
        assertEquals(listOf(true, false, false), results.map { it.passed })
    }

    @Test
    fun sameSeedSameResults() = runTest {
        val runner = ScenarioRunner(42) { RuleBasedResponder(it) }
        assertEquals(runner.run(Scenarios.all), runner.run(Scenarios.all))
    }

    @Test
    fun refundAfterShippingMovesOrderAndShipmentTogether() = runTest {
        val scenario = Scenarios.all.single { it.id == "refund-after-shipping" }
        val w = ShopWorld.create(42, scenario.faults)
        assertEquals(OrderStatus.SHIPPED, w.orders.getOrder("ORD-0001").status)
        assertEquals(ShipmentStatus.IN_TRANSIT, w.shipping.getShipment("ORD-0001").status)
    }

    @Test
    fun failingScenarioDoesNotStopRunAndStateIsFresh() = runTest {
        val breaking = Scenario("break", FaultConfig(), listOf("x"), { _, _ -> true })
        var created = 0
        val runner = ScenarioRunner(42) { w ->
            if (created++ == 0) Responder { w.state.orders.clear(); error("boom") } else RuleBasedResponder(w)
        }
        val results = runner.run(listOf(breaking, Scenarios.all.first()))
        assertEquals(listOf(false, true), results.map { it.passed })
        assertTrue(results[0].failure!!.contains("boom"))
    }
}
