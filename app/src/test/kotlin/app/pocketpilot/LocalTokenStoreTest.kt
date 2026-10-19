package app.pocketpilot

import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.ui.claudeCodeCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalTokenStoreTest {
    private class MemorySecrets : SecretStore {
        val values = HashMap<String, String>()

        override fun get(name: String) = values[name]

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    @Test
    fun `token survives a restart and changes on rotate`() {
        val dir = Files.createTempDirectory("tokens").toFile()
        val secrets = MemorySecrets()
        val first = LocalTokenStore(secrets, dir).current()
        assertTrue(first.startsWith("pp_") && first.length > 40)
        assertEquals(first, LocalTokenStore(secrets, dir).current())

        val store = LocalTokenStore(secrets, dir)
        store.rotate()
        assertNotEquals(first, store.current())
        assertEquals(store.current(), LocalTokenStore(secrets, dir).current())
    }

    @Test
    fun `a token saved as a plain file by an older version moves into the secret store`() {
        val dir = Files.createTempDirectory("legacy").toFile()
        val legacy = "pp_" + "a".repeat(43)
        java.io.File(dir, "local_token").writeText(legacy)
        val secrets = MemorySecrets()

        assertEquals(legacy, LocalTokenStore(secrets, dir).current())
        assertEquals(legacy, secrets.values["local_token"])
        assertEquals(false, java.io.File(dir, "local_token").exists())
    }

    @Test
    fun `claude code command carries the url and bearer token`() {
        assertEquals(
            "claude mcp add --transport http pocketpilot http://127.0.0.1:8765/mcp --header \"Authorization: Bearer pp_x\"",
            claudeCodeCommand("http://127.0.0.1:8765/mcp", "pp_x"),
        )
    }
}
