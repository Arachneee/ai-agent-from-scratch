package aiagent.shop.cli

import aiagent.shop.fault.FaultPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
