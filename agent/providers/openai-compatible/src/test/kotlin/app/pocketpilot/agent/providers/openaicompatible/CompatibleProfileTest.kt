package app.pocketpilot.agent.providers.openaicompatible

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CompatibleProfileTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val http =
        HttpClient(
            MockEngine { request ->
                requests += request
                respond("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""", HttpStatusCode.OK)
            },
        )

    private val request = ModelRequest("qwen2.5:7b", "", listOf(Message.user("Hi")))

    @Test
    fun ollamaHasNoKeyAndUsesItsPort() =
        runTest {
            val provider = compatibleProvider(http, CompatiblePresets.ollama("192.168.1.20"))
            assertEquals("openai-compatible:ollama", provider.id)
            assertEquals("Ollama", provider.displayName)
            provider.stream(request).toList()
            assertEquals("http://192.168.1.20:11434/v1/chat/completions", requests.single().url.toString())
            assertNull(requests.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun hostedPresetSendsKeyAndHeaders() =
        runTest {
            compatibleProvider(http, CompatiblePresets.openRouter("or-key")).stream(request).toList()
            val sent = requests.single()
            assertEquals("https://openrouter.ai/api/v1/chat/completions", sent.url.toString())
            assertEquals("Bearer or-key", sent.headers[HttpHeaders.Authorization])
            assertEquals("PocketPilot", sent.headers["X-Title"])
        }

    @Test
    fun presetUrls() {
        assertEquals("https://api.deepseek.com/v1", CompatiblePresets.deepSeek("k").baseUrl)
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", CompatiblePresets.qwen("k").baseUrl)
        assertEquals("https://api.groq.com/openai/v1", CompatiblePresets.groq("k").baseUrl)
        assertEquals("https://api.mistral.ai/v1", CompatiblePresets.mistral("k").baseUrl)
        assertEquals("http://studio.lan:1234/v1", CompatiblePresets.lmStudio("studio.lan").baseUrl)
        assertEquals("http://[fd00::5]:8080/v1", CompatiblePresets.llamaCpp("fd00::5").baseUrl)
    }

    @Test
    fun toStringHidesKey() {
        assertFalse("secret" in CompatiblePresets.groq("secret").toString())
    }

    @Test
    fun rejectsBadProfiles() {
        assertFailsWith<IllegalArgumentException> { CompatibleProfile(" ", "http://x/v1", null) }
        assertFailsWith<IllegalArgumentException> { CompatibleProfile("x", "ftp://x/v1", null) }
    }
}
