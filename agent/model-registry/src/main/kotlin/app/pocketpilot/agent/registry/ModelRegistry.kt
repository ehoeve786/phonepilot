package app.pocketpilot.agent.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a model calls tools. */
@Serializable
enum class ToolSupport {
    /** The provider's own tool-calling API. */
    @SerialName("native")
    NATIVE,

    /** No reliable native tool calls; the runtime asks for a JSON object in the reply instead. */
    @SerialName("json-fallback")
    JSON_FALLBACK,

    /** The model cannot drive tools. */
    @SerialName("none")
    NONE,
}

/** Prices per million tokens, in [currency]. */
@Serializable
data class Pricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val currency: String = "USD",
)

/**
 * What the agent needs to know about one model (spec section 9, model registry). Null numbers are
 * unknown; callers fall back to conservative values.
 */
@Serializable
data class ModelDescriptor(
    /** `anthropic`, `openai`, `gemini` or `openai-compatible:<profile>`, as in `ModelProvider.id`. */
    val provider: String,
    val model: String,
    val tools: ToolSupport = ToolSupport.NATIVE,
    val vision: Boolean = false,
    /** The longest image edge, in pixels, worth sending; larger screenshots are scaled down to it. */
    val maxImageEdge: Int? = null,
    val contextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val pricing: Pricing? = null,
    val notes: String? = null,
)

/**
 * Looks up [ModelDescriptor]s: user [overrides] first, then the [bundled] list shipped with the app.
 * Signed remote updates to the list are not supported yet.
 */
class ModelRegistry(
    private val bundled: List<ModelDescriptor>,
    private val overrides: List<ModelDescriptor> = emptyList(),
) {
    /**
     * The descriptor for [model] at [provider], or null when nothing matches. Matches, best first: the
     * same provider and model; the same model at any OpenAI-compatible profile (models such as
     * `qwen2.5:7b` behave the same in Ollama and LM Studio); then the same with the model's `:tag`
     * dropped, so `gemma3:12b` finds `gemma3`. Within each step an override wins over the bundled list.
     */
    fun find(
        provider: String,
        model: String,
    ): ModelDescriptor? {
        val candidates = overrides + bundled
        val untagged = model.substringBefore(':')
        val steps =
            listOf<(ModelDescriptor) -> Boolean>(
                { it.provider == provider && sameModel(it.model, model) },
                { sameFamily(it.provider, provider) && sameModel(it.model, model) },
                { it.provider == provider && untagged != model && sameModel(it.model, untagged) },
                { sameFamily(it.provider, provider) && untagged != model && sameModel(it.model, untagged) },
            )
        for (matches in steps) {
            candidates.firstOrNull(matches)?.let { return it }
        }
        return null
    }

    /** The descriptor for [model], or conservative defaults when the registry does not know it. */
    fun describe(
        provider: String,
        model: String,
    ): ModelDescriptor = find(provider, model) ?: defaultDescriptor(provider, model)

    companion object {
        private const val RESOURCE = "/app/pocketpilot/agent/registry/models.json"
        private const val DEFAULT_CONTEXT_TOKENS = 8192
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 2048
        private const val FAMILY_PREFIX = "openai-compatible"

        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        /** The registry with the list shipped in the app. */
        fun loadBundled(overrides: List<ModelDescriptor> = emptyList()): ModelRegistry {
            val text =
                checkNotNull(ModelRegistry::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
                    .use { it.readBytes().decodeToString() }
            return ModelRegistry(parse(text), overrides)
        }

        /** Descriptors from a JSON array, the format of the bundled list and of user overrides. */
        fun parse(text: String): List<ModelDescriptor> = json.decodeFromString(text)

        /** Unknown models get native tools (most current models have them), no images and a small context. */
        fun defaultDescriptor(
            provider: String,
            model: String,
        ) = ModelDescriptor(
            provider = provider,
            model = model,
            tools = ToolSupport.NATIVE,
            vision = false,
            contextTokens = DEFAULT_CONTEXT_TOKENS,
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
            notes = "Not in the model registry; using conservative defaults.",
        )

        private fun sameModel(
            a: String,
            b: String,
        ) = a.removeSuffix(":latest") == b.removeSuffix(":latest")

        /** True for two OpenAI-compatible profiles, or a profile and the bare family name. */
        private fun sameFamily(
            entry: String,
            requested: String,
        ) = requested.startsWith("$FAMILY_PREFIX:") && (entry == FAMILY_PREFIX || entry.startsWith("$FAMILY_PREFIX:"))
    }
}

/** The price of a call in the descriptor's currency, or null when its pricing is unknown. */
fun estimateCost(
    descriptor: ModelDescriptor,
    inputTokens: Int,
    outputTokens: Int,
): Double? {
    val pricing = descriptor.pricing ?: return null
    return (inputTokens * pricing.inputPerMillion + outputTokens * pricing.outputPerMillion) / TOKENS_PER_MILLION
}

private const val TOKENS_PER_MILLION = 1_000_000.0
