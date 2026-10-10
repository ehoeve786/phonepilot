package app.pocketpilot.agent.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelRegistryTest {
    private val ollamaQwen = ModelDescriptor("openai-compatible:ollama", "qwen2.5:7b", contextTokens = 32768)
    private val lmStudioQwen = ModelDescriptor("openai-compatible:lmstudio", "qwen2.5:7b", contextTokens = 16384)
    private val gemma = ModelDescriptor("openai-compatible:ollama", "gemma3", ToolSupport.JSON_FALLBACK, vision = true)
    private val registry = ModelRegistry(listOf(ollamaQwen, lmStudioQwen, gemma))

    @Test
    fun exactMatchWinsOverFamilyMatch() {
        assertSame(lmStudioQwen, registry.find("openai-compatible:lmstudio", "qwen2.5:7b"))
        assertSame(ollamaQwen, registry.find("openai-compatible:ollama", "qwen2.5:7b"))
    }

    @Test
    fun otherCompatibleProfilesFallBackToTheFamily() {
        assertSame(ollamaQwen, registry.find("openai-compatible:llamacpp", "qwen2.5:7b"))
        assertNull(registry.find("openai", "qwen2.5:7b"))
    }

    @Test
    fun tagsFallBackToTheUntaggedModel() {
        assertSame(gemma, registry.find("openai-compatible:ollama", "gemma3:latest"))
        assertSame(gemma, registry.find("openai-compatible:ollama", "gemma3:12b"))
        assertNull(registry.find("openai-compatible:ollama", "qwen2.5:14b"))
    }

    @Test
    fun overrideWins() {
        val override = ollamaQwen.copy(vision = true)
        val withOverride = ModelRegistry(listOf(ollamaQwen), listOf(override))
        assertSame(override, withOverride.find("openai-compatible:ollama", "qwen2.5:7b"))
    }

    @Test
    fun unknownModelsGetConservativeDefaults() {
        assertNull(registry.find("openai", "gpt-unknown"))
        val described = registry.describe("openai", "gpt-unknown")
        assertEquals("openai", described.provider)
        assertEquals("gpt-unknown", described.model)
        assertEquals(ToolSupport.NATIVE, described.tools)
        assertFalse(described.vision)
        assertEquals(8192, described.contextTokens)
    }

    @Test
    fun bundledListParses() {
        val bundled = ModelRegistry.loadBundled()
        val sonnet = bundled.describe("anthropic", "claude-sonnet-5-5")
        assertTrue(sonnet.vision)
        assertEquals(ToolSupport.NATIVE, sonnet.tools)
        assertEquals(ToolSupport.JSON_FALLBACK, bundled.describe("openai-compatible:ollama", "gemma3:4b").tools)
        assertNull(bundled.describe("openai-compatible:deepseek", "deepseek-chat").pricing)
        for (provider in listOf("anthropic", "openai", "gemini")) {
            assertTrue(bundled.find(provider, "missing") == null)
        }
    }

    @Test
    fun parsesWireNames() {
        val parsed =
            ModelRegistry.parse(
                """
                [{"provider":"x","model":"m","tools":"json-fallback","pricing":{"inputPerMillion":1.5,"outputPerMillion":6}}]
                """.trimIndent(),
            )
        assertEquals(ToolSupport.JSON_FALLBACK, parsed.single().tools)
        assertEquals(Pricing(1.5, 6.0, "USD"), parsed.single().pricing)
    }

    @Test
    fun estimatesCost() {
        val priced = ModelDescriptor("openai", "m", pricing = Pricing(0.4, 1.6))
        assertEquals(0.4 + 0.8, estimateCost(priced, 1_000_000, 500_000)!!, 1e-9)
        assertNull(estimateCost(ollamaQwen, 1000, 1000))
    }
}
