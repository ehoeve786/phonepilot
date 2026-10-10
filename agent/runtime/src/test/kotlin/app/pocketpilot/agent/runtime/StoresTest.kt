package app.pocketpilot.agent.runtime

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoresTest {
    private val dir = Files.createTempDirectory("agent").toFile()

    @Test
    fun `profiles survive a restart and keep their ids`() {
        val file = dir.resolve("profiles.json")
        val ollama = AgentProfile("p2", "Home Ollama", ProviderKind.OPENAI_COMPATIBLE, "qwen2.5:7b", "http://192.168.8.20:11434/v1")
        ProfileStore(file).apply {
            save(AgentProfile("p1", "Gemini", ProviderKind.GEMINI, "gemini-2.5-flash"))
            save(ollama)
            save(ollama.copy(model = "qwen2.5:14b"))
            remove("p1")
        }
        assertEquals(listOf(ollama.copy(model = "qwen2.5:14b")), ProfileStore(file).profiles.value)
    }

    @Test
    fun `profiles name compatible servers by host and only limit cost on paid APIs`() {
        val ollama = AgentProfile("p", "Ollama", ProviderKind.OPENAI_COMPATIBLE, "qwen2.5:7b", "http://192.168.8.20:11434/v1")
        assertEquals("openai-compatible:192.168.8.20", ollama.providerId)
        assertNull(ollama.budgets().maxCostUsd)
        assertEquals(0.50, AgentProfile("g", "Gemini", ProviderKind.GEMINI, "gemini-2.5-flash").budgets().maxCostUsd)
    }

    @Test
    fun `key names are safe file names`() {
        val profile = AgentProfile("dd0a46de-9862-4200-a43d-712e7ed1895b", "Gemini", ProviderKind.GEMINI, "gemini-2.5-flash")
        assertEquals("agent_key_dd0a46de_9862_4200_a43d_712e7ed1895b", profile.keyName)
    }

    @Test
    fun `run store lists newest first and drops the oldest`() =
        runTest {
            val store = FileRunStore(dir.resolve("runs"), keep = 2)
            for (id in listOf("01A", "01B", "01C")) store.save(RunState(id, "goal $id", "fake", "m", 0))
            store.save(RunState("01C", "goal 01C", "fake", "m", 0, status = RunStatus.FINISHED, outcome = "ok"))
            val runs = store.list()
            assertEquals(listOf("01C", "01B"), runs.map(RunState::id))
            assertEquals("ok", runs.first().outcome)
        }
}
