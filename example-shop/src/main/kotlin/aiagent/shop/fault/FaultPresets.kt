package aiagent.shop.fault

object FaultPresets {
    private val presets: Map<String, FaultConfig> = mapOf(
        "none" to FaultConfig(),
        "slow" to allServices(ServiceFaults(latencyMs = 500L..2000L)),
        "flaky" to allServices(ServiceFaults(errorRate = 0.2)),
        "rate-limited" to allServices(ServiceFaults(rateLimitEvery = 3)),
        "stale" to allServices(ServiceFaults(staleReadRate = 0.5)),
    )

    fun get(name: String): FaultConfig =
        presets[name] ?: throw IllegalArgumentException("알 수 없는 장애 설정 '$name'. 사용 가능: ${presets.keys.joinToString()}")

    private fun allServices(faults: ServiceFaults) = FaultConfig(ServiceName.entries.associateWith { faults })
}
