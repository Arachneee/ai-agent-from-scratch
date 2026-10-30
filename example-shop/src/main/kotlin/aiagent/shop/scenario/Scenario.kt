package aiagent.shop.scenario

import aiagent.shop.ShopWorld
import aiagent.shop.fault.FaultConfig

data class Turn(val user: String, val reply: String)

class Scenario(
    val id: String,
    val faults: FaultConfig,
    val messages: List<String>,
    val check: (ShopWorld, List<Turn>) -> Boolean,
)

data class ScenarioResult(
    val id: String,
    val passed: Boolean,
    val failure: String?,
    val serviceCalls: Int,
    val injectedDelayMs: Long,
)
