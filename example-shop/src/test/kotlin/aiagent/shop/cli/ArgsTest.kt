package aiagent.shop.cli

import aiagent.shop.ShopWorld
import aiagent.shop.fault.FaultPresets
import aiagent.shop.fault.ServiceException
import aiagent.shop.fault.ServiceFaults
import aiagent.shop.fault.ServiceName
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgsTest {
    @Test
    fun defaults() = assertEquals(CliArgs("chat", 42, "none"), parseArgs(arrayOf("chat")))

    @Test
    fun options() = assertEquals(
        CliArgs("chat", 7, "flaky"),
        parseArgs(arrayOf("chat", "--seed", "7", "--faults", "flaky")),
    )

    @Test
    fun scenariosRejectsFaultsOption() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("scenarios", "--faults", "flaky")) }
    }

    @Test
    fun unknownCommandFails() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("serve")) }
    }

    @Test
    fun unknownPresetFails() {
        assertFailsWith<IllegalArgumentException> { FaultPresets.get("chaos") }
    }

    @Test
    fun responderOption() = assertEquals(
        CliArgs("chat", 42, "none", responder = "model"),
        parseArgs(arrayOf("chat", "--responder", "model")),
    )

    @Test
    fun unknownResponderFails() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("chat", "--responder", "magic")) }
    }

    @Test
    fun scenariosRejectsResponderOption() {
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("scenarios", "--responder", "model")) }
    }

    @Test
    fun missingApiKeyIsReported() {
        val e = assertFailsWith<IllegalArgumentException> { requireApiKey { null } }
        assertTrue("OPENAI_API_KEY" in e.message!!)
        assertFailsWith<IllegalArgumentException> { requireApiKey { " " } }
        assertEquals("sk-x", requireApiKey { "sk-x" })
    }

    @Test
    fun apiKeyIsTrimmedAndControlCharactersAreRejectedWithoutEcho() {
        assertEquals("sk-x", requireApiKey { "sk-x\r\n" })
        val e = assertFailsWith<IllegalArgumentException> { requireApiKey { "sk-sec ret" } }
        assertFalse("sk-sec" in e.message!!)
    }

    @Test
    fun downPresetFailsEveryCall() = runTest {
        val world = ShopWorld.create(42, FaultPresets.get("down"))

        repeat(5) { assertFailsWith<ServiceException.Unavailable> { world.shipping.getShipment("ORD-0001") } }
    }

    @Test
    fun slowShippingDelaysOnlyShipping() {
        val services = FaultPresets.get("slow-shipping").services

        assertEquals(mapOf(ServiceName.SHIPPING to ServiceFaults(latencyMs = 2000L..2000L)), services)
    }

    @Test
    fun longChatParsesSeedAndSummary() {
        assertEquals(LongChatArgs(7, "drop"), parseLongChatArgs(arrayOf("long-chat", "--seed", "7", "--summary", "drop")))
        assertEquals(LongChatArgs(42, "model"), parseLongChatArgs(arrayOf("long-chat")))
        assertEquals(LongChatArgs(42, "cached"), parseLongChatArgs(arrayOf("long-chat", "--summary", "cached")))
        assertFailsWith<IllegalArgumentException> { parseLongChatArgs(arrayOf("long-chat", "--summary", "maybe")) }
    }
}
