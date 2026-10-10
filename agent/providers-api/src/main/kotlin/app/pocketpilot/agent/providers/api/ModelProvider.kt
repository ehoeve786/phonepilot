package app.pocketpilot.agent.providers.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * One AI provider the agent can call (spec section 9): Anthropic, OpenAI, Gemini or an
 * OpenAI-compatible endpoint such as Ollama. Adapters translate the normalized [ModelRequest] to the
 * provider's wire format and its reply back to [ModelEvent]s.
 */
interface ModelProvider {
    /** `anthropic`, `openai`, `gemini` or `openai-compatible:<profile>`. */
    val id: String

    val displayName: String

    /** Models the configured key or endpoint can use. */
    suspend fun listModels(): List<ModelInfo>

    /**
     * Sends [request] and emits the reply: text, tool calls, usage, then one [ModelEvent.Stop], or a
     * single [ModelEvent.Error] instead. The flow does not throw for provider or network failures.
     */
    fun stream(request: ModelRequest): Flow<ModelEvent>
}

data class ModelInfo(
    val id: String,
    val displayName: String = id,
)

data class ModelRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolDef> = emptyList(),
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double? = null,
    val toolChoice: ToolChoice? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

/**
 * A tool the model may call. [name] must already be wire-safe (letters, digits, `_` and `-`, at
 * most 64 characters), which every provider accepts; the runtime maps it back to the tool's name.
 */
data class ToolDef(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments, an object schema. */
    val inputSchema: JsonObject,
)

enum class ToolChoice {
    /** The model decides. */
    AUTO,

    /** The model must call some tool. */
    ANY,

    /** The model must not call tools. */
    NONE,
}

enum class Role {
    USER,
    ASSISTANT,
}

/** A normalized message; tool results travel in USER messages, as every provider allows. */
data class Message(
    val role: Role,
    val parts: List<Part>,
) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Part.Text(text)))
    }
}

sealed interface Part {
    data class Text(
        val text: String,
    ) : Part

    class Image(
        val bytes: ByteArray,
        /** `image/png`, `image/jpeg` or `image/webp`. */
        val mime: String,
    ) : Part {
        override fun toString(): String = "Image($mime, ${bytes.size} bytes)"
    }

    /** A call the model made, echoed back in the assistant message. */
    data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : Part

    /** The outcome of the call [id] to the tool [name]; [parts] holds text and images. */
    data class ToolResult(
        val id: String,
        val name: String,
        val parts: List<Part>,
        val isError: Boolean = false,
    ) : Part
}

sealed interface ModelEvent {
    data class TextDelta(
        val text: String,
    ) : ModelEvent

    data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : ModelEvent

    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
    ) : ModelEvent

    data class Stop(
        val reason: StopReason,
    ) : ModelEvent

    data class Error(
        val code: ModelErrorCode,
        val message: String,
        /** Set for [ModelErrorCode.RATE_LIMIT] when the provider said how long to wait. */
        val retryAfterMs: Long? = null,
    ) : ModelEvent {
        val retryable: Boolean
            get() = code == ModelErrorCode.RATE_LIMIT || code == ModelErrorCode.SERVER || code == ModelErrorCode.NETWORK
    }
}

enum class StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    OTHER,
}

enum class ModelErrorCode {
    AUTH,
    RATE_LIMIT,
    CONTEXT_TOO_LONG,
    SERVER,
    NETWORK,
    INVALID_REQUEST,
}
