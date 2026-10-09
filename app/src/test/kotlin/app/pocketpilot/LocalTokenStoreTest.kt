package app.pocketpilot

import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.ui.claudeCodeCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalTokenStoreTest {
    @Test
    fun `token survives a restart and changes on rotate`() {
        val dir = Files.createTempDirectory("tokens").toFile()
        val first = LocalTokenStore(dir).current()
        assertTrue(first.startsWith("pp_") && first.length > 40)
        assertEquals(first, LocalTokenStore(dir).current())

        val store = LocalTokenStore(dir)
        store.rotate()
        assertNotEquals(first, store.current())
        assertEquals(store.current(), LocalTokenStore(dir).current())
    }

    @Test
    fun `claude code command carries the url and bearer token`() {
        assertEquals(
            "claude mcp add --transport http pocketpilot http://127.0.0.1:8765/mcp --header \"Authorization: Bearer pp_x\"",
            claudeCodeCommand("http://127.0.0.1:8765/mcp", "pp_x"),
        )
    }
}
