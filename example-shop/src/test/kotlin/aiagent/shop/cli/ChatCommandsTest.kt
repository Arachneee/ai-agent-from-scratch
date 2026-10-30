package aiagent.shop.cli

import aiagent.shop.agent.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatCommandsTest {
    private val settings = ModelSettings(model = "gpt-4.1-nano", temperature = 0.2, maxTokens = 100, showRaw = false)

    @Test
    fun invalidCommandValueKeepsSettings() {
        for (line in listOf("/temperature 뜨겁게", "/temperature 3", "/max-tokens -1", "/max-tokens 0", "/raw maybe", "/model", "/nope")) {
            val result = applyChatCommand(line, settings)
            assertEquals(settings, result.settings, line)
            assertTrue("사용법" in result.message, "$line: ${result.message}")
        }
    }

    @Test
    fun validCommandsChangeSettings() {
        assertEquals(settings.copy(temperature = 0.7), applyChatCommand("/temperature 0.7", settings).settings)
        assertEquals(settings.copy(temperature = null), applyChatCommand("/temperature off", settings).settings)
        assertEquals(settings.copy(maxTokens = null), applyChatCommand("/max-tokens off", settings).settings)
        assertEquals(settings.copy(maxTokens = 5), applyChatCommand("/max-tokens 5", settings).settings)
        assertEquals(settings.copy(model = "gpt-x"), applyChatCommand("/model gpt-x", settings).settings)
        assertEquals(settings.copy(showRaw = true), applyChatCommand("/raw on", settings).settings)
    }

    @Test
    fun resetIsFlagged() {
        val result = applyChatCommand("/reset", settings)
        assertTrue(result.reset)
        assertEquals(settings, result.settings)
    }
}
