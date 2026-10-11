package app.pocketpilot.agent.providers.anthropic

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelErrors
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.agent.providers.api.ToolChoice
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64

/**
 * [ModelProvider] for the Anthropic Messages API.
 *
 * Each [stream] call sends one non-streaming `POST /v1/messages` and emits the events of the whole
 * reply once it arrives. We chose this over server-sent events for robustness: a reply is either
 * complete or an error, with no half-parsed tool calls to recover from.
 */
class AnthropicProvider(
    private val http: HttpClient,
    private val apiKey: String,
    baseUrl: String = "https://api.anthropic.com",
) : ModelProvider {
    private val baseUrl = baseUrl.trimEnd('/')

    override val id: String = "anthropic"

    override val displayName: String = "Anthropic"

    /** Lists every model the key can use, following the API's pagination. Throws [IOException] on failure. */
    override suspend fun listModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        var afterId: String? = null
        do {
            val response =
                http.get("$baseUrl/v1/models") {
                    authHeaders()
                    parameter("limit", PAGE_SIZE)
                    afterId?.let { parameter("after_id", it) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}: ${body.take(MAX_ERROR_BODY)}")
            val page = Json.parseToJsonElement(body).jsonObject
            page["data"]?.jsonArray?.forEach { item ->
                val model = item.jsonObject
                val modelId = model.string("id") ?: return@forEach
                models += ModelInfo(modelId, model.string("display_name") ?: modelId)
            }
            afterId = page.string("last_id").takeIf { (page["has_more"] as? JsonPrimitive)?.booleanOrNull == true }
        } while (afterId != null)
        return models
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            val events =
                try {
                    val response =
                        http.post("$baseUrl/v1/messages") {
                            authHeaders()
                            setBody(TextContent(requestBody(request).toString(), ContentType.Application.Json))
                        }
                    readReply(response)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listOf(ModelErrors.network(e))
                }
            events.forEach { emit(it) }
        }

    private fun HttpRequestBuilder.authHeaders() {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    private suspend fun readReply(response: HttpResponse): List<ModelEvent> {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return listOf(ModelErrors.fromHttp(response.status.value, body, response.headers[HttpHeaders.RetryAfter]))
        }
        return try {
            parseReply(Json.parseToJsonElement(body).jsonObject)
        } catch (e: IllegalArgumentException) {
            listOf(ModelEvent.Error(ModelErrorCode.SERVER, "Unreadable reply: ${e.message}"))
        }
    }

    internal companion object {
        const val API_VERSION = "2023-06-01"
        private const val PAGE_SIZE = 1000
        private const val MAX_ERROR_BODY = 500

        fun requestBody(request: ModelRequest): JsonObject =
            buildJsonObject {
                put("model", request.model)
                put("max_tokens", request.maxTokens)
                if (request.system.isNotBlank()) put("system", request.system)
                request.temperature?.let { put("temperature", it) }
                put("messages", messages(request.messages))
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.inputSchema)
                            }
                        }
                    }
                }
                request.toolChoice?.let { choice ->
                    putJsonObject("tool_choice") {
                        put(
                            "type",
                            when (choice) {
                                ToolChoice.AUTO -> "auto"
                                ToolChoice.ANY -> "any"
                                ToolChoice.NONE -> "none"
                            },
                        )
                    }
                }
            }

        /**
         * Merges consecutive messages with the same role, since the API requires turns to alternate,
         * and puts tool results first in each user turn, where the API expects them.
         */
        private fun messages(messages: List<Message>): JsonArray {
            val turns = mutableListOf<Pair<Role, MutableList<Part>>>()
            for (message in messages) {
                if (message.parts.isEmpty()) continue
                val last = turns.lastOrNull()
                if (last != null && last.first == message.role) {
                    last.second += message.parts
                } else {
                    turns += message.role to message.parts.toMutableList()
                }
            }
            return buildJsonArray {
                for ((role, parts) in turns) {
                    val ordered = if (role == Role.USER) parts.sortedBy { it !is Part.ToolResult } else parts
                    addJsonObject {
                        put("role", if (role == Role.USER) "user" else "assistant")
                        put("content", JsonArray(ordered.map(::block)))
                    }
                }
            }
        }

        private fun block(part: Part): JsonObject =
            when (part) {
                is Part.Text -> {
                    buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    }
                }

                is Part.Image -> {
                    buildJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", part.mime)
                            put("data", Base64.getEncoder().encodeToString(part.bytes))
                        }
                    }
                }

                is Part.ToolCall -> {
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", part.id)
                        put("name", part.name)
                        put("input", parseArgs(part.argsJson))
                    }
                }

                is Part.ToolResult -> {
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", part.id)
                        val content = part.parts.filter { it is Part.Text || it is Part.Image }
                        if (content.isNotEmpty()) put("content", JsonArray(content.map(::block)))
                        if (part.isError) put("is_error", true)
                    }
                }
            }

        /** The call's arguments as an object; anything else becomes `{}`, which the API accepts. */
        private fun parseArgs(argsJson: String): JsonObject =
            try {
                Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: JsonObject(emptyMap())

        fun parseReply(reply: JsonObject): List<ModelEvent> {
            val events = mutableListOf<ModelEvent>()
            reply["content"]?.jsonArray?.forEach { element ->
                val block = element.jsonObject
                when (block.string("type")) {
                    "text" -> {
                        block.string("text")?.takeIf { it.isNotEmpty() }?.let { events += ModelEvent.TextDelta(it) }
                    }

                    "tool_use" -> {
                        events +=
                            ModelEvent.ToolCall(
                                id = block.string("id").orEmpty(),
                                name = block.string("name").orEmpty(),
                                argsJson = (block["input"] ?: JsonObject(emptyMap())).toString(),
                            )
                    }
                }
            }
            (reply["usage"] as? JsonObject)?.let { usage ->
                events += ModelEvent.Usage(usage.int("input_tokens"), usage.int("output_tokens"))
            }
            val reason =
                when (reply.string("stop_reason")) {
                    "end_turn" -> StopReason.END_TURN
                    "tool_use" -> StopReason.TOOL_USE
                    "max_tokens" -> StopReason.MAX_TOKENS
                    else -> StopReason.OTHER
                }
            events += ModelEvent.Stop(reason)
            return events
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    }
}
