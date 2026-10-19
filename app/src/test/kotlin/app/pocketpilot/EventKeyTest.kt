package app.pocketpilot

import app.pocketpilot.automation.EventKey
import app.pocketpilot.network.api.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventKeyTest {
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
    fun `the key is made once and kept`() {
        val secrets = MemorySecrets()
        val first = EventKey.get(secrets)
        assertTrue(first.startsWith("ppe_"))
        assertEquals(first, EventKey.get(secrets))
    }

    @Test
    fun `only the exact key matches`() {
        assertTrue(EventKey.matches("ppe_abc", "ppe_abc"))
        assertFalse(EventKey.matches("ppe_abc", "ppe_abd"))
        assertFalse(EventKey.matches("ppe_abc", null))
    }
}
