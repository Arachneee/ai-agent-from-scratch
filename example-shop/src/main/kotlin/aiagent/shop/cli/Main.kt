package aiagent.shop.cli

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

data class CliArgs(val command: String, val seed: Long, val faults: String, val responder: String = "rule")

private val COMMANDS = setOf("chat", "scenarios")
private val RESPONDERS = setOf("rule", "model")

private const val USAGE =
    "사용법: chat [--seed <정수>] [--faults <none|slow|flaky|rate-limited|stale>] [--responder <rule|model>], " +
        "scenarios [--seed <정수>], 또는 burst [--count <1~100>] [--concurrency <1~100>] [--model <이름>]"

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
            if (apiKey == null) chatLoop(RuleBasedResponder(world)) else modelChat(apiKey, world)
        }
    }
}

private suspend fun modelChat(apiKey: String, world: ShopWorld) {
    var settings = ModelSettings(ModelPricing.DEFAULT_MODEL, temperature = null, maxTokens = null, showRaw = false)
    val http = HttpClient(CIO) { rawExchangeLog(enabled = { settings.showRaw }, sink = ::println) }
    val responder = ModelResponder(OpenAiClient(http, apiKey), ShopTools(world), { settings })
    println(CHAT_COMMAND_USAGE)
    chatLoop(responder) { line ->
        val result = applyChatCommand(line, settings)
        settings = result.settings
        if (result.reset) responder.reset()
        println(result.message)
    }
    http.close()
}

private suspend fun chatLoop(responder: Responder, onCommand: ((String) -> Unit)? = null) {
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
        runChatTurn(responder, line, ::println)
    }
}

/** 한 턴을 처리하고 출력한다. 예외가 나도 채팅이 끝나지 않도록 오류 문장으로 보여 준다. */
suspend fun runChatTurn(responder: Responder, line: String, out: (String) -> Unit) {
    try {
        val reply = responder.respond(line)
        if (responder is ModelResponder) {
            responder.lastToolCalls.forEach { out("[툴] ${it.name} ${it.arguments} 결과: ${it.result}") }
        }
        out(reply)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServiceException) {
        out("[서비스 오류] ${e.message}")
    } catch (e: Exception) {
        out("[오류] ${e::class.simpleName}: ${e.message}")
    }
    if (responder is ModelResponder) responder.lastUsages.forEach { out(usageLine(it)) }
}

private fun usageLine(usage: CallUsage): String {
    val cost = usage.costUsd?.let { "약 %.4f원".format(it * ModelPricing.KRW_PER_USD) } ?: "가격 미등록"
    return "[토큰] 입력 ${usage.inputTokens} · 출력 ${usage.outputTokens} · $cost"
}
