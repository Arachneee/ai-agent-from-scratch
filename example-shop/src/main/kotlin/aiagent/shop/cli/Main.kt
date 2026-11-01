package aiagent.shop.cli

import aiagent.framework.agent.AgentEvent
import aiagent.framework.model.ModelPricing
import aiagent.framework.model.OpenAiClient
import aiagent.framework.model.rawExchangeLog
import aiagent.shop.ShopWorld
import aiagent.shop.agent.CallUsage
import aiagent.shop.agent.ModelResponder
import aiagent.shop.agent.ModelSettings
import aiagent.shop.agent.ShopTools
import aiagent.shop.fault.FaultPresets
import aiagent.shop.fault.ServiceException
import aiagent.shop.scenario.Responder
import aiagent.shop.scenario.RuleBasedResponder
import aiagent.shop.scenario.ScenarioRunner
import aiagent.shop.scenario.Scenarios
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.TimeSource

data class CliArgs(val command: String, val seed: Long, val faults: String, val responder: String = "rule")

private val COMMANDS = setOf("chat", "scenarios")
private val RESPONDERS = setOf("rule", "model")

private const val USAGE =
    "사용법: chat [--seed <정수>] [--faults <none|slow|flaky|rate-limited|stale|down|slow-shipping>] [--responder <rule|model>], " +
        "scenarios [--seed <정수>], burst [--count <1~100>] [--concurrency <1~100>] [--model <이름>], 또는 long-chat [--seed <정수>] [--summary <none|drop|model|cached>]"

fun parseArgs(args: Array<String>): CliArgs {
    val command = args.firstOrNull()
    require(command in COMMANDS) { "알 수 없는 명령 '$command'" }
    var seed = 42L
    var faults = "none"
    var responder = "rule"
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]}에 값이 없습니다")
        when (args[i]) {
            "--seed" -> seed = value.toLongOrNull() ?: throw IllegalArgumentException("시드는 정수여야 합니다: $value")
            "--faults" -> {
                require(command == "chat") { "scenarios는 시나리오마다 장애 설정이 정해져 있어 --faults를 받지 않습니다" }
                faults = value
            }
            "--responder" -> {
                require(command == "chat") { "scenarios는 비용 때문에 정규식 응답기만 써서 --responder를 받지 않습니다" }
                require(value in RESPONDERS) { "알 수 없는 응답기 '$value'" }
                responder = value
            }
            else -> throw IllegalArgumentException("알 수 없는 옵션 '${args[i]}'")
        }
        i += 2
    }
    return CliArgs(command!!, seed, faults, responder)
}

fun requireApiKey(env: (String) -> String?): String {
    val key = env("OPENAI_API_KEY")?.trim()
    require(!key.isNullOrEmpty()) { "모델 응답기를 쓰려면 OPENAI_API_KEY 환경 변수에 API 키를 설정해야 합니다" }
    // 헤더에 넣을 수 없는 문자가 있으면 Ktor 예외 메시지에 키 원문이 실리므로, 키를 보여 주지 않고 여기서 거부한다.
    require(key.none { it.isWhitespace() || it.isISOControl() }) { "OPENAI_API_KEY에 공백이나 제어 문자가 들어 있습니다" }
    return key
}

fun main(args: Array<String>) = runBlocking {
    if (args.firstOrNull() == "burst") {
        val (burstArgs, apiKey) = try {
            parseBurstArgs(args) to requireApiKey(System::getenv)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(2)
        }
        runBurst(burstArgs, apiKey)
        return@runBlocking
    }

    if (args.firstOrNull() == "long-chat") {
        val (longChatArgs, apiKey) = try {
            parseLongChatArgs(args) to requireApiKey(System::getenv)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(2)
        }
        runLongChatCommand(longChatArgs, apiKey)
        return@runBlocking
    }

    val (cli, faults, apiKey) = try {
        val parsed = parseArgs(args)
        Triple(parsed, FaultPresets.get(parsed.faults), if (parsed.responder == "model") requireApiKey(System::getenv) else null)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }

    when (cli.command) {
        "scenarios" -> {
            val runner = ScenarioRunner(cli.seed) { RuleBasedResponder(it) }
            println(runner.report(runner.run(Scenarios.all)))
        }
        "chat" -> {
            val world = ShopWorld.create(cli.seed, faults)
            val console = ConsoleOutput {
                print(it)
                System.out.flush()
            }
            if (apiKey == null) chatLoop(console, RuleBasedResponder(world)) else modelChat(console, apiKey, world)
        }
    }
}

private suspend fun modelChat(console: ConsoleOutput, apiKey: String, world: ShopWorld) {
    var settings = ModelSettings(ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false)
    val http = HttpClient(CIO) { rawExchangeLog(enabled = { settings.showRaw }, sink = console::line) }
    val responder = ModelResponder(OpenAiClient(http, apiKey), ShopTools(world), { settings })
    println(CHAT_COMMAND_USAGE)
    chatLoop(console, responder) { line ->
        val result = applyChatCommand(line, settings)
        settings = result.settings
        if (result.reset) responder.reset()
        println(result.message)
    }
    http.close()
}

private suspend fun chatLoop(console: ConsoleOutput, responder: Responder, onCommand: ((String) -> Unit)? = null) {
    while (true) {
        print("> ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line == "/quit") break
        if (line.isEmpty()) continue
        if (onCommand != null && line.startsWith("/")) {
            onCommand(line)
            continue
        }
        runChatTurn(responder, line, console::line, console::fragment)
    }
}

/**
 * 한 턴을 처리하고 출력한다. 예외가 나도 채팅이 끝나지 않도록 오류 문장으로 보여 준다.
 * 줄은 [out]으로, 모델이 쓰는 글자 조각은 줄바꿈 없이 [write]로 찍는다. 글자 조각 뒤에 줄이 오면 먼저 줄을 바꾸는 일은 [out]이 맡는다.
 * 툴은 실행하기 직전에 `[툴 호출]` 줄로 보여 주고, 스트리밍으로 이미 찍은 답은 다시 찍지 않는다.
 * 모델 응답기이면 마지막에 이 턴에서 처음 무언가를 출력하기까지의 시간과 답을 출력하기까지의 시간을 보여 준다.
 */
suspend fun runChatTurn(
    responder: Responder,
    line: String,
    out: (String) -> Unit,
    write: (String) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    val start = timeSource.markNow()
    var firstOutput: Duration? = null
    val streamed = StringBuilder()
    val printLine = { text: String ->
        if (firstOutput == null) firstOutput = start.elapsedNow()
        out(text)
    }
    val printFragment = { text: String ->
        if (firstOutput == null) firstOutput = start.elapsedNow()
        write(text)
        streamed.append(text)
    }
    try {
        val reply = if (responder is ModelResponder) {
            responder.respond(line) { event ->
                when (event) {
                    is AgentEvent.TextDelta -> printFragment(event.text)
                    is AgentEvent.HistorySummarized -> printLine("[기록 요약] ${event.summary ?: "요약 없이 버림"}")
                    is AgentEvent.ToolCallStarted -> {
                        printLine("[툴 호출] ${event.name} ${event.arguments}")
                        streamed.clear()
                    }
                }
            }
        } else {
            responder.respond(line)
        }
        if (reply != streamed.toString()) printLine(reply)
        if (responder is ModelResponder) {
            responder.lastToolCalls.forEach { printLine("[툴] ${it.name} ${it.arguments} 결과: ${it.result}") }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServiceException) {
        printLine("[서비스 오류] ${e.message}")
    } catch (e: Exception) {
        printLine("[오류] ${e::class.simpleName}: ${e.message}")
    }
    val total = start.elapsedNow()
    if (responder is ModelResponder) {
        responder.lastUsages.forEach { printLine(usageLine(it)) }
        printLine("[시간] 첫 출력 %.1f초 · 전체 %.1f초".format(firstOutput!!.inWholeMilliseconds / 1000.0, total.inWholeMilliseconds / 1000.0))
    }
}

private fun usageLine(usage: CallUsage): String {
    val cost = usage.costUsd?.let { "약 %.4f원".format(it * ModelPricing.KRW_PER_USD) } ?: "가격 미등록"
    return "[토큰] 입력 ${usage.inputTokens} (캐시 ${usage.cachedTokens}) · 출력 ${usage.outputTokens} · $cost"
}
