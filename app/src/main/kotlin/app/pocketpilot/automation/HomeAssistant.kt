package app.pocketpilot.automation

import app.pocketpilot.core.automation.AutomationFailure
import app.pocketpilot.core.automation.AutomationJson
import app.pocketpilot.core.automation.RuleStore
import app.pocketpilot.network.api.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The owner's Home Assistant: its address is in the automation settings and its long-lived access token
 * in the encrypted secret store. Actions and conditions use the REST API; "entity changes" triggers keep
 * one websocket open, subscribed to just the entities rules watch.
 */
class HomeAssistant(
    private val store: RuleStore,
    private val secrets: SecretStore,
    http: HttpClient,
    private val scope: CoroutineScope,
) {
    private val client = http.config { install(WebSockets) { pingIntervalMillis = PING_MS } }
    private var watcher: Job? = null
    private var watched: Set<String> = emptySet()
    private var onChange: (String, String, String) -> Unit = { _, _, _ -> }

    val configured: Boolean get() = baseUrl() != null && token() != null

    fun saveToken(token: String) {
        if (token.isBlank()) secrets.remove(TOKEN) else secrets.put(TOKEN, token.trim())
    }

    val hasToken: Boolean get() = token() != null

    /** The entity's state and attributes, as Home Assistant returns them. */
    suspend fun entity(entity: String): JsonObject {
        val reply = request("GET", "/api/states/${URLEncoder.encode(entity.trim(), "UTF-8")}", null)
        return runCatching { AutomationJson.json.parseToJsonElement(reply).jsonObject }.getOrNull()
            ?: throw AutomationFailure("Home Assistant sent an unexpected reply for $entity")
    }

    /** The entity's state, or null when Home Assistant is not set up or cannot be reached. */
    suspend fun stateOf(entity: String): String? =
        if (!configured) {
            null
        } else {
            runCatching { entity(entity)["state"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }

    /** Calls the action [service] ("light.turn_on") with [data], adding [entity] as its target. */
    suspend fun call(
        service: String,
        entity: String,
        data: JsonObject,
    ): String {
        val domain = service.substringBefore('.', "").trim()
        val name = service.substringAfter('.', "").trim()
        if (domain.isEmpty() || name.isEmpty()) throw AutomationFailure("Give the action as domain.action, like light.turn_on")
        val body =
            JsonObject(
                data +
                    if (entity.isBlank()) emptyMap() else mapOf("entity_id" to JsonPrimitive(entity.trim())),
            )
        return request("POST", "/api/services/$domain/$name", body.toString())
    }

    /** Keeps a websocket open for [entities] and calls [emit] with each state change; empty stops it. */
    @Synchronized
    fun watch(
        entities: Set<String>,
        emit: (entity: String, state: String, previous: String) -> Unit,
    ) {
        onChange = emit
        if (entities == watched && watcher?.isActive == true) return
        watcher?.cancel()
        watched = entities
        if (entities.isEmpty()) {
            watcher = null
            return
        }
        watcher =
            scope.launch {
                var backoff = MIN_BACKOFF_MS
                while (isActive) {
                    val url = baseUrl()
                    val token = token()
                    if (url != null && token != null) {
                        try {
                            listen(url, token, entities) { entity, state, previous -> onChange(entity, state, previous) }
                            backoff = MIN_BACKOFF_MS
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Reconnects below; Home Assistant restarts and phone network changes end the socket.
                        }
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
    }

    fun stop() = watch(emptySet()) { _, _, _ -> }

    /** Connects again with the current address and token, after the owner changed them. */
    @Synchronized
    fun reconnect() {
        val entities = watched
        if (entities.isEmpty()) return
        watcher?.cancel()
        watched = emptySet()
        watch(entities, onChange)
    }

    private suspend fun listen(
        base: String,
        token: String,
        entities: Set<String>,
        emit: (String, String, String) -> Unit,
    ) {
        val socket = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        client.webSocket(socket) {
            val states = HashMap<String, String>()
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching { AutomationJson.json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                when (message["type"]?.jsonPrimitive?.contentOrNull) {
                    "auth_required" -> {
                        send(
                            Frame.Text(
                                buildJsonObject {
                                    put("type", "auth")
                                    put("access_token", token)
                                }.toString(),
                            ),
                        )
                    }

                    "auth_invalid" -> {
                        throw AutomationFailure("Home Assistant refused the access token")
                    }

                    "auth_ok" -> {
                        send(
                            Frame.Text(
                                buildJsonObject {
                                    put("id", 1)
                                    put("type", "subscribe_entities")
                                    put("entity_ids", JsonArray(entities.map(::JsonPrimitive)))
                                }.toString(),
                            ),
                        )
                    }

                    "event" -> {
                        val event = message["event"] as? JsonObject ?: continue
                        // "a" holds every entity's state on subscribing, "c" the changes since.
                        (event["a"] as? JsonObject)?.forEach { (id, value) ->
                            (value as? JsonObject)
                                ?.get("s")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.let { states[id] = it }
                        }
                        (event["c"] as? JsonObject)?.forEach { (id, value) ->
                            val added = (value as? JsonObject)?.get("+") as? JsonObject ?: return@forEach
                            val state = added["s"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val previous = states.put(id, state).orEmpty()
                            if (state != previous) emit(id, state, previous)
                        }
                    }
                }
            }
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): String {
        val base = baseUrl() ?: throw AutomationFailure(NOT_SET_UP)
        val token = token() ?: throw AutomationFailure(NOT_SET_UP)
        return withContext(Dispatchers.IO) {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
                val reply = stream?.bufferedReader()?.use { it.readText().take(MAX_REPLY_CHARS) }.orEmpty()
                when {
                    status == HTTP_UNAUTHORIZED -> throw AutomationFailure("Home Assistant refused the access token")
                    status == HTTP_NOT_FOUND -> throw AutomationFailure("Home Assistant has no such entity or action")
                    status >= HTTP_ERROR -> throw AutomationFailure("Home Assistant answered HTTP $status: ${reply.take(MAX_ERROR_CHARS)}")
                }
                reply
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun baseUrl(): String? =
        store.data.value.settings.homeAssistantUrl
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    private fun token(): String? = secrets.get(TOKEN)?.takeIf { it.isNotBlank() }

    private companion object {
        const val TOKEN = "home_assistant_token"
        const val NOT_SET_UP = "Home Assistant is not set up; add its address and a token under Automations settings"
        const val PING_MS = 30_000L
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 300_000L
        const val TIMEOUT_MS = 15_000
        const val HTTP_ERROR = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val MAX_REPLY_CHARS = 100_000
        const val MAX_ERROR_CHARS = 200
    }
}
