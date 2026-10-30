package aiagent.framework.model

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders

/**
 * 실제로 오간 요청과 응답의 헤더와 본문 원문을 [sink]로 내보낸다.
 * [enabled]는 호출마다 다시 읽으므로 실행 중에 켜고 끌 수 있다. `Authorization` 값은 `***`로 가린다.
 */
fun HttpClientConfig<*>.rawExchangeLog(enabled: () -> Boolean, sink: (String) -> Unit) {
    install(Logging) {
        level = LogLevel.ALL
        logger = object : Logger {
            override fun log(message: String) {
                if (enabled()) sink(message)
            }
        }
        sanitizeHeader { it == HttpHeaders.Authorization }
    }
}
