package aiagent.shop.cli

import aiagent.framework.agent.CachedModelSummarizer
import aiagent.framework.agent.DropSummarizer
import aiagent.framework.agent.ModelSummarizer
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

data class LongChatArgs(val seed: Long, val summary: String)

private val SUMMARIES = setOf("none", "drop", "model", "cached")

/** 기록을 줄일 때 쓰는 기준이다. 성공 기준인 41턴 입력 2,000토큰보다 한 턴 사이에 늘어나는 양만큼 낮게 잡았다. */
const val LONG_CHAT_HISTORY_TOKEN_LIMIT = 1_800

/**
 * `long-chat [--seed <정수>] [--summary <none|drop|model|cached>]`을 읽는다. 기본값은 시드 42와 `model`이다.
 * `none`은 기록을 줄이지 않고, `drop`은 잘라 낸 기록을 버리며, `model`은 잘라 낸 기록만 모델로 요약하고,
 * `cached`는 직전 요청 끝에 요약 지시를 붙여 캐시를 살린 채 요약한다.
 */
fun parseLongChatArgs(args: Array<String>): LongChatArgs {
    var seed = 42L
    var summary = "model"
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]}에 값이 없습니다")
        when (args[i]) {
            "--seed" -> seed = value.toLongOrNull() ?: throw IllegalArgumentException("시드는 정수여야 합니다: $value")
            "--summary" -> {
                require(value in SUMMARIES) { "알 수 없는 요약 방식 '$value'" }
                summary = value
            }
            else -> throw IllegalArgumentException("알 수 없는 옵션 '${args[i]}'")
        }
        i += 2
    }
    return LongChatArgs(seed, summary)
}

/** 장애 없이 [LongChat] 상담을 한 번 돌린다. */
suspend fun runLongChatCommand(args: LongChatArgs, apiKey: String) {
    val world = ShopWorld.create(args.seed, FaultPresets.get("none"))
    val settings = ModelSettings(
        ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false,
        historyTokenLimit = if (args.summary == "none") null else LONG_CHAT_HISTORY_TOKEN_LIMIT,
    )
    val http = HttpClient(CIO)
    val client = OpenAiClient(http, apiKey)
    val summarizer = when (args.summary) {
        "model" -> ModelSummarizer(client, settings.model, ModelResponder.SUMMARY_INSTRUCTION)
        "cached" -> CachedModelSummarizer(client, ModelResponder.CACHED_SUMMARY_INSTRUCTION)
        else -> DropSummarizer
    }
    val responder = ModelResponder(client, ShopTools(world), { settings }, summarizer = summarizer)
    runLongChat(responder, LongChat.messages, LongChat.checkpoints, ::println)
    http.close()
}
