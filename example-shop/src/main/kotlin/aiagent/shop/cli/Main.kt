package aiagent.shop.cli

import aiagent.shop.ShopWorld
import aiagent.shop.fault.FaultPresets
import aiagent.shop.fault.ServiceException
import aiagent.shop.scenario.RuleBasedResponder
import aiagent.shop.scenario.ScenarioRunner
import aiagent.shop.scenario.Scenarios
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

data class CliArgs(val command: String, val seed: Long, val faults: String)

private val COMMANDS = setOf("chat", "scenarios")

private const val USAGE = "사용법: chat [--seed <정수>] [--faults <none|slow|flaky|rate-limited|stale>] 또는 scenarios [--seed <정수>]"

fun parseArgs(args: Array<String>): CliArgs {
    val command = args.firstOrNull()
    require(command in COMMANDS) { "알 수 없는 명령 '$command'" }
    var seed = 42L
    var faults = "none"
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]}에 값이 없습니다")
        when (args[i]) {
            "--seed" -> seed = value.toLongOrNull() ?: throw IllegalArgumentException("시드는 정수여야 합니다: $value")
            "--faults" -> {
                require(command == "chat") { "scenarios는 시나리오마다 장애 설정이 정해져 있어 --faults를 받지 않습니다" }
                faults = value
            }
            else -> throw IllegalArgumentException("알 수 없는 옵션 '${args[i]}'")
        }
        i += 2
    }
    return CliArgs(command!!, seed, faults)
}

fun main(args: Array<String>) = runBlocking {
    val (cli, faults) = try {
        val parsed = parseArgs(args)
        parsed to FaultPresets.get(parsed.faults)
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
            val responder = RuleBasedResponder(ShopWorld.create(cli.seed, faults))
            while (true) {
                print("> ")
                System.out.flush()
                val line = readlnOrNull()?.trim() ?: break
                if (line == "/quit") break
                if (line.isEmpty()) continue
                try {
                    println(responder.respond(line))
                } catch (e: ServiceException) {
                    println("[서비스 오류] ${e.message}")
                }
            }
        }
    }
}
