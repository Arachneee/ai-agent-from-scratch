package aiagent.shop.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleOutputTest {
    @Test
    fun lineAfterFragmentStartsOnNewLine() {
        val printed = StringBuilder()
        val console = ConsoleOutput { printed.append(it) }

        console.fragment("안녕하세요.")
        console.line("RESPONSE: 200 OK")

        assertEquals("안녕하세요.\nRESPONSE: 200 OK\n", printed.toString())
    }

    @Test
    fun fragmentEndingWithNewlineNeedsNoExtraBreak() {
        val printed = StringBuilder()
        val console = ConsoleOutput { printed.append(it) }

        console.fragment("안녕하세요.")
        console.fragment("\n")
        console.line("[토큰] 입력 1")

        assertEquals("안녕하세요.\n[토큰] 입력 1\n", printed.toString())
    }
}
