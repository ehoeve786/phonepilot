package app.pocketpilot.security

import app.pocketpilot.core.policy.ToolMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolModeCodecTest {
    @Test
    fun `modes survive a save and a load`() {
        val modes = mapOf("ui.tap" to ToolMode.BLOCK, "shell.exec" to ToolMode.ASK)

        assertEquals(modes, decodeToolModes(encodeToolModes(modes)))
    }

    @Test
    fun `a Block set before the move is kept, and unknown modes are dropped`() {
        val legacy = mapOf("ui.tap" to ToolMode.BLOCK.name, "old.tool" to "NOT_A_MODE")

        assertEquals(mapOf("ui.tap" to ToolMode.BLOCK), modesFromLegacy(legacy))
    }

    @Test
    fun `an empty or garbled saved value gives no modes`() {
        assertEquals(emptyMap(), decodeToolModes(""))
        assertEquals(emptyMap(), decodeToolModes("no equals sign here\n=BLOCK"))
    }
}
