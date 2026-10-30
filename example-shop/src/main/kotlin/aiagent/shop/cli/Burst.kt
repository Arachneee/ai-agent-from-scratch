package aiagent.shop.cli

import aiagent.framework.model.ChatApiException
import aiagent.framework.model.ChatRequest
import aiagent.framework.model.ModelPricing
import aiagent.framework.model.OpenAiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Headers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.measureTimedValue

data class BurstArgs(val count: Int, val concurrency: Int, val model: String?)

private const val MAX_BURST_COUNT = 100

/** 네트워크 예외처럼 HTTP 상태 코드를 받지 못한 요청을 세는 값이다. */
private const val NO_STATUS = -1

fun parseBurstArgs(args: Array<String>): BurstArgs {
    var count = 20
    var concurrency = 10
    var model: String? = null
    var i = 1
    while (i < args.size) {
        val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]}에 값이 없습니다")
        when (args[i]) {
            "--count" -> count = value.toIntOrNull() ?: throw IllegalArgumentException("요청 수는 정수여야 합니다: $value")
            "--concurrency" -> concurrency = value.toIntOrNull() ?: throw IllegalArgumentException("동시 실행 수는 정수여야 합니다: $value")
            "--model" -> model = value
            else -> throw IllegalArgumentException("알 수 없는 옵션 '${args[i]}'")
        }
        i += 2
    }
    require(count in 1..MAX_BURST_COUNT) { "요청 수는 1에서 $MAX_BURST_COUNT 사이여야 합니다: $count" }
    require(concurrency in 1..MAX_BURST_COUNT) { "동시 실행 수는 1에서 $MAX_BURST_COUNT 사이여야 합니다: $concurrency" }
    return BurstArgs(count, concurrency, model)
}

/** [send]를 [count]번, 동시에 최대 [concurrency]개씩 실행하고 돌려받은 상태 코드별 건수를 센다. */
suspend fun burst(count: Int, concurrency: Int, send: suspend () -> Int): Map<Int, Int> = coroutineScope {
    val permits = Semaphore(concurrency)
    (1..count).map { async { permits.withPermit { send() } } }
        .awaitAll()
        .groupingBy { it }
        .eachCount()
}

/** 429 응답을 겪어 보려고 짧은 요청을 몰아 보낸다. 요청마다 max_tokens를 1로 줘서 비용을 줄인다. */
suspend fun runBurst(args: BurstArgs, apiKey: String) {
    var lastRateLimitHeaders: Headers? = null
    var first429Headers: Headers? = null
    var first429Message: String? = null
    val captureHeaders = createClientPlugin("CaptureRateLimitHeaders") {
        onResponse { response ->
            lastRateLimitHeaders = response.headers
            if (response.status.value == 429 && first429Headers == null) first429Headers = response.headers
        }
    }
    val http = HttpClient(CIO) { install(captureHeaders) }
    val client = OpenAiClient(http, apiKey)
    val request = ChatRequest(
        model = args.model ?: ModelPricing.DEFAULT_MODEL,
        messages = listOf(ChatRequest.MessageRequest("user", "안녕")),
        maxTokens = 1,
    )

    println("${request.model}에 요청 ${args.count}개를 동시에 최대 ${args.concurrency}개씩 보냅니다.")
    val (counts, elapsed) = measureTimedValue {
        burst(args.count, args.concurrency) {
            client.chat(request).fold(
                onSuccess = { 200 },
                onFailure = { e ->
                    if (e is ChatApiException && e.status == 429 && first429Message == null) first429Message = e.error?.error?.message
                    (e as? ChatApiException)?.status ?: NO_STATUS
                },
            )
        }
    }
    http.close()

    println("걸린 시간: ${elapsed.inWholeMilliseconds}ms")
    println("상태 코드별 건수: " + counts.entries.sortedBy { it.key }.joinToString { (status, n) ->
        "${if (status == NO_STATUS) "네트워크 오류" else status}=$n"
    })
    println("마지막 응답의 속도 제한 헤더:")
    printRateLimitHeaders(lastRateLimitHeaders)
    if (first429Headers != null) {
        println("첫 429 응답의 메시지: ${first429Message ?: "오류 본문을 해석하지 못했습니다"}")
        println("첫 429 응답의 속도 제한 헤더:")
        printRateLimitHeaders(first429Headers)
    }
}

private fun printRateLimitHeaders(headers: Headers?) {
    val lines = headers?.entries()
        ?.filter { (name, _) -> name.lowercase().let { it.startsWith("x-ratelimit-") || it == "retry-after" } }
        ?.sortedBy { it.key.lowercase() }
        ?.map { (name, values) -> "  $name: ${values.joinToString()}" }
        .orEmpty()
    println(lines.ifEmpty { listOf("  (없음)") }.joinToString("\n"))
}
