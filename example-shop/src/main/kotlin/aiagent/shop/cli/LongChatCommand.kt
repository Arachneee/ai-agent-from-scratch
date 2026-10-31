package aiagent.shop.cli

import aiagent.framework.model.ModelPricing
import aiagent.framework.model.OpenAiClient
import aiagent.shop.ShopWorld
import aiagent.shop.agent.ModelResponder
import aiagent.shop.agent.ModelSettings
import aiagent.shop.agent.ShopTools
import aiagent.shop.fault.FaultPresets
import aiagent.shop.scenario.LongChat
import aiagent.shop.scenario.runLongChat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/** `long-chat [--seed <정수>]`에서 시드를 읽는다. 기본값은 42다. */
fun parseLongChatArgs(args: Array<String>): Long {
    var seed = 42L
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]}에 값이 없습니다")
        when (args[i]) {
            "--seed" -> seed = value.toLongOrNull() ?: throw IllegalArgumentException("시드는 정수여야 합니다: $value")
            else -> throw IllegalArgumentException("알 수 없는 옵션 '${args[i]}'")
        }
        i += 2
    }
    return seed
}

/** 장애 없이 [LongChat] 상담을 한 번 돌린다. */
suspend fun runLongChatCommand(seed: Long, apiKey: String) {
    val world = ShopWorld.create(seed, FaultPresets.get("none"))
    val settings = ModelSettings(ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false)
    val http = HttpClient(CIO)
    val responder = ModelResponder(OpenAiClient(http, apiKey), ShopTools(world), { settings })
    runLongChat(responder, LongChat.messages, LongChat.checkpoints, ::println)
    http.close()
}
