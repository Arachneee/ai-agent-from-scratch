package aiagent.shop

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildSmokeTest {
    @Test
    fun runsOnJdk21() {
        assertEquals("21", System.getProperty("java.specification.version"))
    }
}
