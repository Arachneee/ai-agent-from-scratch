package aiagent.shop.cli

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
}
