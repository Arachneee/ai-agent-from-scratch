package aiagent.shop.cli

import aiagent.framework.model.ErrorResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BurstTest {
    @Test
    fun countsStatusCodes() = runTest {
        val statuses = listOf(200, 429, 200, 429, 429)
        val next = AtomicInteger()

        val counts = burst(5, 2) { statuses[next.getAndIncrement()] }

        assertEquals(mapOf(200 to 2, 429 to 3), counts)
    }

    @Test
    fun respectsConcurrency() = runTest {
        val active = AtomicInteger()
        var peak = 0

        burst(10, 3) {
            peak = maxOf(peak, active.incrementAndGet())
            delay(10)
            active.decrementAndGet()
            200
        }

        assertTrue(peak <= 3, "peak=$peak")
        assertEquals(3, peak)
    }

    @Test
    fun burstArgsDefaults() =
        assertEquals(BurstArgs(count = 20, concurrency = 10, model = null), parseBurstArgs(arrayOf("burst")))

    @Test
    fun burstArgsOptions() = assertEquals(
        BurstArgs(count = 50, concurrency = 25, model = "gpt-4.1-mini"),
        parseBurstArgs(arrayOf("burst", "--count", "50", "--concurrency", "25", "--model", "gpt-4.1-mini")),
    )

    @Test
    fun burstArgsRejectOutOfRangeCount() {
        assertFailsWith<IllegalArgumentException> { parseBurstArgs(arrayOf("burst", "--count", "0")) }
        assertFailsWith<IllegalArgumentException> { parseBurstArgs(arrayOf("burst", "--count", "1000")) }
        assertFailsWith<IllegalArgumentException> { parseBurstArgs(arrayOf("burst", "--concurrency", "0")) }
    }

    @Test
    fun rateLimitDescriptionKeepsCodeAndType() {
        val error = ErrorResponse(ErrorResponse.ErrorDetail("You exceeded your current quota", "insufficient_quota", null, "insufficient_quota"))
        val text = describeError(error)
        assertTrue("code=insufficient_quota" in text, text)
        assertTrue("type=insufficient_quota" in text, text)
        assertTrue("You exceeded your current quota" in text, text)
    }

    @Test
    fun statusWithoutResponseIsNotCalledNetworkError() =
        assertEquals("상태 코드 없음(예외)", statusLabel(-1))
}
