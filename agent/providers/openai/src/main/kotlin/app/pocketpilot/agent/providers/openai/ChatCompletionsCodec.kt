package app.pocketpilot.agent.providers.openai

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelErrors
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.agent.providers.api.ToolChoice
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Translates between the normalized [ModelRequest] and the OpenAI Chat Completions wire format, which
 * OpenAI and most OpenAI-compatible servers (Ollama, LM Studio, DeepSeek, Groq and others) accept.
 */
object ChatCompletionsCodec {
    /** The text that introduces images returned by tools, since a `tool` message can only carry text. */
    const val TOOL_IMAGES_CAPTION = "Screenshot from the last tool call:"

    private val json = Json { ignoreUnknownKeys = true }

    /** The request body for `POST /chat/completions`, without streaming. */
    fun encodeRequest(request: ModelRequest): JsonObject =
        buildJsonObject {
            put("model", request.model)
            put("messages", encodeMessages(request))
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.inputSchema)
                            }
                        }
                    }
                }
            }
            request.toolChoice?.let { put("tool_choice", encodeToolChoice(it)) }
            put("max_tokens", request.maxTokens)
            request.temperature?.let { put("temperature", it) }
        }

    /**
     * The events for one complete Chat Completions reply: text, tool calls, usage, then a stop. Servers
     * that omit tool call ids (Ollama) get one from [newToolCallId]. A reply that is not a usable
     * completion becomes a single [ModelEvent.Error].
     */
    fun decodeResponse(
        body: String,
        newToolCallId: () -> String,
    ): List<ModelEvent> {
        val root =
            try {
                json.parseToJsonElement(body) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return listOf(malformed(body))
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice == null) {
            // OpenRouter and some proxies report failures as an error object in a 200 reply.
            val error = root["error"] as? JsonObject ?: return listOf(malformed(body))
            val status = (error["code"] as? JsonPrimitive)?.intOrNull ?: SERVER_ERROR_STATUS
            return listOf(ModelErrors.fromHttp(status, error.toString()))
        }
        val message = choice["message"] as? JsonObject ?: return listOf(malformed(body))
        val events = mutableListOf<ModelEvent>()
        decodeContent(message["content"]).takeIf { it.isNotEmpty() }?.let { events += ModelEvent.TextDelta(it) }
        val toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { decodeToolCall(it, newToolCallId) }
        events += toolCalls
        (root["usage"] as? JsonObject)?.let { usage ->
            events +=
                ModelEvent.Usage(
                    inputTokens = usage.int("prompt_tokens"),
                    outputTokens = usage.int("completion_tokens"),
                )
        }
        events += ModelEvent.Stop(stopReason(choice.string("finish_reason"), toolCalls.isNotEmpty()))
        return events
    }

    /** The model ids in a `GET /models` reply. */
    fun decodeModels(body: String): List<ModelInfo> {
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.string("id")?.let(::ModelInfo) }
    }

    private fun encodeMessages(request: ModelRequest): JsonArray =
        buildJsonArray {
            if (request.system.isNotEmpty()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.system)
                }
            }
            for (message in request.messages) {
                when (message.role) {
                    Role.USER -> encodeUser(message).forEach { add(it) }
                    Role.ASSISTANT -> add(encodeAssistant(message))
                }
            }
        }

    /**
     * Each tool result becomes its own `tool` message. Those can only hold text, so images from tool
     * results follow in one user message, together with any other content of the normalized message.
     */
    private fun encodeUser(message: Message): List<JsonObject> {
        val results = message.parts.filterIsInstance<Part.ToolResult>()
        val toolMessages =
            results.map { result ->
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.id)
                    put("content", toolResultText(result))
                }
            }
        val withImages = results.filter { result -> result.parts.any { it is Part.Image } }
        val userParts = mutableListOf<Part>()
        if (withImages.size == 1) {
            userParts += Part.Text(TOOL_IMAGES_CAPTION)
            userParts += withImages.single().parts.filterIsInstance<Part.Image>()
        } else {
            for (result in withImages) {
                userParts += Part.Text("Images from the ${result.name} call ${result.id}:")
                userParts += result.parts.filterIsInstance<Part.Image>()
            }
        }
        userParts += message.parts.filter { it is Part.Text || it is Part.Image }
        if (userParts.isEmpty()) return toolMessages
        return toolMessages +
            buildJsonObject {
                put("role", "user")
                put("content", encodeUserContent(userParts))
            }
    }

    /**
     * Plain text goes as a string, which every compatible server accepts; content with images goes as
     * parts with base64 data URLs.
     */
    private fun encodeUserContent(parts: List<Part>): JsonElement {
        if (parts.none { it is Part.Image }) {
            return JsonPrimitive(parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text })
        }
        return buildJsonArray {
            for (part in parts) {
                when (part) {
                    is Part.Text -> {
                        addJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        }
                    }

                    is Part.Image -> {
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl(part)) }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    private fun encodeAssistant(message: Message): JsonObject {
        val text = message.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        val calls = message.parts.filterIsInstance<Part.ToolCall>()
        return buildJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty() || calls.isEmpty()) put("content", text)
            if (calls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (call in calls) {
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argsJson)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toolResultText(result: Part.ToolResult): String {
        val text = result.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val body =
            when {
                text.isNotEmpty() -> text
                result.parts.any { it is Part.Image } -> "The result is an image, sent in the next message."
                else -> "(no output)"
            }
        return if (result.isError) "Error: $body" else body
    }

    private fun encodeToolChoice(choice: ToolChoice): String =
        when (choice) {
            ToolChoice.AUTO -> "auto"
            ToolChoice.ANY -> "required"
            ToolChoice.NONE -> "none"
        }

    private fun dataUrl(image: Part.Image): String = "data:${image.mime};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    /** Content is normally a string; some servers send a list of text parts instead. */
    private fun decodeContent(content: JsonElement?): String =
        when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("")
            else -> ""
        }

    private fun decodeToolCall(
        element: JsonElement,
        newToolCallId: () -> String,
    ): ModelEvent.ToolCall? {
        val call = element as? JsonObject ?: return null
        val function = call["function"] as? JsonObject ?: return null
        val name = function.string("name") ?: return null
        // Arguments are a JSON string; a few servers send the object itself.
        val args =
            when (val arguments = function["arguments"]) {
                null, JsonNull -> "{}"
                is JsonPrimitive -> arguments.content.ifBlank { "{}" }
                else -> arguments.toString()
            }
        val id = call.string("id")?.takeIf { it.isNotBlank() } ?: newToolCallId()
        return ModelEvent.ToolCall(id, name, args)
    }

    /** Ollama reports `stop` even when it called tools, so tool calls decide over the reason. */
    private fun stopReason(
        finishReason: String?,
        calledTools: Boolean,
    ): StopReason =
        when {
            finishReason == "length" -> StopReason.MAX_TOKENS
            calledTools || finishReason == "tool_calls" || finishReason == "function_call" -> StopReason.TOOL_USE
            finishReason == "stop" -> StopReason.END_TURN
            else -> StopReason.OTHER
        }

    private fun malformed(body: String) = ModelEvent.Error(ModelErrorCode.SERVER, "Unexpected reply: ${body.take(MAX_MESSAGE)}")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private const val SERVER_ERROR_STATUS = 500
    private const val MAX_MESSAGE = 500
}
