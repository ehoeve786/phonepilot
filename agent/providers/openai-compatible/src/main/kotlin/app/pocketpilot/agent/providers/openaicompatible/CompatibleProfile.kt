package app.pocketpilot.agent.providers.openaicompatible

import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.openai.OpenAiProvider
import io.ktor.client.HttpClient

/**
 * A server that speaks the OpenAI Chat Completions API at [baseUrl] (the URL that ends before
 * `/chat/completions`). [name] identifies the profile in the provider id `openai-compatible:<name>`;
 * [displayName] is what the user sees.
 */
data class CompatibleProfile(
    val name: String,
    val baseUrl: String,
    val apiKey: String?,
    val headers: Map<String, String> = emptyMap(),
    val displayName: String = name,
) {
    init {
        require(name.isNotBlank()) { "A profile needs a name" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "Not an http(s) URL: $baseUrl" }
    }

    override fun toString(): String = "CompatibleProfile($name, $baseUrl, key=${if (apiKey.isNullOrBlank()) "none" else "set"})"
}

/** The provider for [profile], with the id `openai-compatible:<name>`. */
fun compatibleProvider(
    http: HttpClient,
    profile: CompatibleProfile,
): ModelProvider =
    OpenAiProvider(
        http = http,
        apiKey = profile.apiKey,
        baseUrl = profile.baseUrl,
        id = "openai-compatible:${profile.name}",
        displayName = profile.displayName,
        extraHeaders = profile.headers,
    )

/**
 * Profiles for well-known OpenAI-compatible services. Hosted services take an API key; local servers
 * take the [host] of the computer running them (a name or address on the home network) and usually no
 * key.
 */
object CompatiblePresets {
    fun deepSeek(apiKey: String) = CompatibleProfile("deepseek", "https://api.deepseek.com/v1", apiKey, displayName = "DeepSeek")

    /** Qwen through Alibaba Cloud Model Studio (DashScope), international region. */
    fun qwen(apiKey: String) =
        CompatibleProfile("qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", apiKey, displayName = "Qwen (DashScope)")

    fun openRouter(apiKey: String) =
        CompatibleProfile(
            "openrouter",
            "https://openrouter.ai/api/v1",
            apiKey,
            // OpenRouter shows this name in its usage pages.
            headers = mapOf("X-Title" to "PocketPilot"),
            displayName = "OpenRouter",
        )

    fun groq(apiKey: String) = CompatibleProfile("groq", "https://api.groq.com/openai/v1", apiKey, displayName = "Groq")

    fun mistral(apiKey: String) = CompatibleProfile("mistral", "https://api.mistral.ai/v1", apiKey, displayName = "Mistral")

    fun ollama(host: String) = CompatibleProfile("ollama", local(host, OLLAMA_PORT), apiKey = null, displayName = "Ollama")

    fun lmStudio(
        host: String,
        apiKey: String? = null,
    ) = CompatibleProfile("lmstudio", local(host, LM_STUDIO_PORT), apiKey, displayName = "LM Studio")

    fun llamaCpp(
        host: String,
        apiKey: String? = null,
    ) = CompatibleProfile("llamacpp", local(host, LLAMA_CPP_PORT), apiKey, displayName = "llama.cpp")

    private fun local(
        host: String,
        port: Int,
    ): String {
        val trimmed = host.trim()
        // A bare IPv6 address needs brackets in a URL.
        val bracketed = if (':' in trimmed && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
        return "http://$bracketed:$port/v1"
    }

    private const val OLLAMA_PORT = 11434
    private const val LM_STUDIO_PORT = 1234
    private const val LLAMA_CPP_PORT = 8080
}
