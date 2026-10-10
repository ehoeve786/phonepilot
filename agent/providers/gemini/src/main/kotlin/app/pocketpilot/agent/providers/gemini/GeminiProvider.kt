package app.pocketpilot.agent.providers.gemini

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
import java.util.concurrent.atomic.AtomicLong

/**
 * [ModelProvider] for the Gemini API (`generateContent`).
 *
 * Each [stream] call sends one non-streaming request and emits the events of the whole reply once it
 * arrives. We chose this over `streamGenerateContent` for robustness: a reply is either complete or
 * an error, with no half-parsed function calls to recover from.
 *
 * Gemini does not always give function calls ids, so calls without one get `call_<n>`, numbered per
 * provider instance. Thinking models attach a thought signature to their function calls and reject
 * the next request unless it is sent back; the provider remembers recent signatures by call id and
 * returns them when the call is echoed in the conversation.
 */
class GeminiProvider(
    private val http: HttpClient,
    private val apiKey: String,
    baseUrl: String = "https://generativelanguage.googleapis.com",
) : ModelProvider {
    private val baseUrl = baseUrl.trimEnd('/')
    private val nextCallId = AtomicLong()
    private val signatures =
        object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_SIGNATURES
        }

    override val id: String = "gemini"

    override val displayName: String = "Google Gemini"

    /** Lists models that support `generateContent`, following pagination. Throws [IOException] on failure. */
    override suspend fun listModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        var pageToken: String? = null
        do {
            val response =
                http.get("$baseUrl/v1beta/models") {
                    header(API_KEY_HEADER, apiKey)
                    parameter("pageSize", PAGE_SIZE)
                    pageToken?.let { parameter("pageToken", it) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}: ${body.take(MAX_ERROR_BODY)}")
            val page = Json.parseToJsonElement(body).jsonObject
            page["models"]?.jsonArray?.forEach { item ->
                val model = item.jsonObject
                val methods = (model["supportedGenerationMethods"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
                val name = model.string("name")?.removePrefix("models/")
                if (name != null && methods?.contains("generateContent") == true) {
                    models += ModelInfo(name, model.string("displayName") ?: name)
                }
            }
            pageToken = page.string("nextPageToken")?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return models
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            val events =
                try {
                    val response =
                        http.post("$baseUrl/v1beta/models/${request.model.removePrefix("models/")}:generateContent") {
                            header(API_KEY_HEADER, apiKey)
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

    private suspend fun readReply(response: HttpResponse): List<ModelEvent> {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) return listOf(httpError(response.status.value, body, response.headers[HttpHeaders.RetryAfter]))
        return try {
            parseReply(Json.parseToJsonElement(body).jsonObject)
        } catch (e: IllegalArgumentException) {
            listOf(ModelEvent.Error(ModelErrorCode.SERVER, "Unreadable reply: ${e.message}"))
        }
    }

    internal fun requestBody(request: ModelRequest): JsonObject =
        buildJsonObject {
            if (request.system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", request.system) } }
                }
            }
            put("contents", contents(request.messages))
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            request.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    // Gemini rejects an object schema without properties, so a tool without arguments has no parameters.
                                    val parameters = GeminiSchema.sanitize(tool.inputSchema)
                                    if (parameters["properties"] != null) put("parameters", parameters)
                                }
                            }
                        }
                    }
                }
            }
            request.toolChoice?.let { choice ->
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") {
                        put(
                            "mode",
                            when (choice) {
                                ToolChoice.AUTO -> "AUTO"
                                ToolChoice.ANY -> "ANY"
                                ToolChoice.NONE -> "NONE"
                            },
                        )
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens)
                request.temperature?.let { put("temperature", it) }
            }
        }

    /** Merges consecutive messages with the same role into one turn, since Gemini expects turns to alternate. */
    private fun contents(messages: List<Message>): JsonArray {
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
                addJsonObject {
                    put("role", if (role == Role.USER) "user" else "model")
                    put("parts", JsonArray(wireParts(parts)))
                }
            }
        }
    }

    /**
     * A turn's parts on the wire. Function responses come first, then the images tools returned
     * (a function response itself holds only JSON), then the rest in order.
     */
    private fun wireParts(parts: List<Part>): List<JsonObject> {
        val results = parts.filterIsInstance<Part.ToolResult>()
        val responses = results.map(::functionResponse)
        val toolImages = results.flatMap { result -> result.parts.filterIsInstance<Part.Image>().map(::inlineData) }
        val rest =
            parts.mapNotNull { part ->
                when (part) {
                    is Part.Text -> buildJsonObject { put("text", part.text) }
                    is Part.Image -> inlineData(part)
                    is Part.ToolCall -> functionCall(part)
                    is Part.ToolResult -> null
                }
            }
        return responses + toolImages + rest
    }

    private fun inlineData(image: Part.Image) =
        buildJsonObject {
            putJsonObject("inlineData") {
                put("mimeType", image.mime)
                put("data", Base64.getEncoder().encodeToString(image.bytes))
            }
        }

    private fun functionCall(call: Part.ToolCall) =
        buildJsonObject {
            putJsonObject("functionCall") {
                put("name", call.name)
                put("args", parseArgs(call.argsJson))
            }
            synchronized(signatures) { signatures[call.id] }?.let { put("thoughtSignature", it) }
        }

    private fun functionResponse(result: Part.ToolResult) =
        buildJsonObject {
            putJsonObject("functionResponse") {
                put("name", result.name)
                putJsonObject("response") {
                    put("content", result.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
                    put("isError", result.isError)
                }
            }
        }

    internal fun parseReply(reply: JsonObject): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        val candidate = (reply["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        var calledTool = false
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        for (element in parts) {
            val part = element.jsonObject
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val call = part["functionCall"] as? JsonObject
            if (call != null) {
                calledTool = true
                val callId = call.string("id")?.takeIf { it.isNotEmpty() } ?: "call_${nextCallId.getAndIncrement()}"
                part.string("thoughtSignature")?.let { signature -> synchronized(signatures) { signatures[callId] = signature } }
                events += ModelEvent.ToolCall(callId, call.string("name").orEmpty(), (call["args"] ?: JsonObject(emptyMap())).toString())
            } else {
                part.string("text")?.takeIf { it.isNotEmpty() }?.let { events += ModelEvent.TextDelta(it) }
            }
        }
        (reply["usageMetadata"] as? JsonObject)?.let { usage ->
            // Thinking tokens are billed as output but counted apart from the candidates.
            events += ModelEvent.Usage(usage.int("promptTokenCount"), usage.int("candidatesTokenCount") + usage.int("thoughtsTokenCount"))
        }
        val reason =
            when (candidate?.string("finishReason")) {
                "STOP" -> if (calledTool) StopReason.TOOL_USE else StopReason.END_TURN
                "MAX_TOKENS" -> StopReason.MAX_TOKENS
                else -> StopReason.OTHER
            }
        events += ModelEvent.Stop(reason)
        return events
    }

    private companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
        const val PAGE_SIZE = 1000
        const val MAX_ERROR_BODY = 500
        const val MAX_SIGNATURES = 256
        val RETRY_DELAY = Regex(""""retryDelay"\s*:\s*"([\d.]+)s"""")

        /**
         * Gemini answers a bad key with 400 `API_KEY_INVALID` and gives the rate-limit wait in the body
         * (`RetryInfo.retryDelay`) rather than a header; both are folded in here.
         */
        fun httpError(
            status: Int,
            body: String,
            retryAfter: String?,
        ): ModelEvent.Error {
            val error = ModelErrors.fromHttp(status, body, retryAfter ?: RETRY_DELAY.find(body)?.groupValues?.get(1))
            return if (status == 400 && "API_KEY_INVALID" in body) error.copy(code = ModelErrorCode.AUTH) else error
        }

        /** The call's arguments as an object; anything else becomes `{}`. */
        fun parseArgs(argsJson: String): JsonObject =
            try {
                Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: JsonObject(emptyMap())

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    }
}
