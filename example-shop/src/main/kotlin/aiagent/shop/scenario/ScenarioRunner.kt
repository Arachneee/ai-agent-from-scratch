package aiagent.shop.scenario

import aiagent.shop.ShopWorld
import java.util.Locale

/** 시나리오마다 같은 시드로 새 세계를 만들어 응답기를 돌리고 결과를 숫자로 남긴다. */
class ScenarioRunner(val seed: Long, val responderFactory: (ShopWorld) -> Responder) {
    suspend fun run(scenarios: List<Scenario>): List<ScenarioResult> = scenarios.map { run(it) }

    private suspend fun run(scenario: Scenario): ScenarioResult {
        val world = ShopWorld.create(seed, scenario.faults)
        val responder = responderFactory(world)
        val turns = mutableListOf<Turn>()
        val failure = try {
            scenario.messages.forEach { turns += Turn(it, responder.respond(it)) }
            if (scenario.check(world, turns)) null else "검사 실패"
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message}"
        }
        return ScenarioResult(scenario.id, failure == null, failure, world.injector.totalCalls, world.injector.injectedDelayMs)
    }

    fun report(results: List<ScenarioResult>): String = buildString {
        appendLine("시나리오 결과 (seed=$seed)")
        results.forEach { r ->
            val detail = "서비스 호출 ${r.serviceCalls}회, 주입 지연 ${r.injectedDelayMs}ms"
            appendLine(if (r.passed) "[성공] ${r.id}: $detail" else "[실패] ${r.id}: ${r.failure}, $detail")
        }
        val passed = results.count { it.passed }
        val rate = if (results.isEmpty()) 0.0 else passed * 100.0 / results.size
        append("합계: ${results.size}개 중 ${passed}개 성공 (${String.format(Locale.ROOT, "%.1f", rate)}%)")
    }
}
