package app.pocketpilot.agent.providers.openai

import app.pocketpilot.agent.providers.api.ModelErrors
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * The OpenAI Chat Completions API, and any server that speaks it at [baseUrl] (see
 * `openai-compatible`). Requests are not streamed: each [stream] call sends one request, waits for the
 * whole reply, then emits its events, so text arrives as a single [ModelEvent.TextDelta].
 *
 * Sends `max_tokens`, which every compatible server accepts; OpenAI's reasoning models want
 * `max_completion_tokens` instead and are not supported yet.
 */
class OpenAiProvider(
    private val http: HttpClient,
    private val apiKey: String?,
    baseUrl: String = DEFAULT_BASE_URL,
    override val id: String = "openai",
    override val displayName: String = "OpenAI",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ModelProvider {
    private val baseUrl = baseUrl.trimEnd('/')
    private val toolCallIds = AtomicLong()

    /**
     * Lists the models from `GET /models`. Throws [IOException] when the request fails, with the same
     * message an equivalent [ModelEvent.Error] would carry.
     */
    override suspend fun listModels(): List<ModelInfo> {
        val response = http.get("$baseUrl/models") { configure() }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw IOException(httpError(response, body).message)
        return ChatCompletionsCodec.decodeModels(body)
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            val events =
                try {
                    val response =
                        http.post("$baseUrl/chat/completions") {
                            configure()
                            setBody(TextContent(ChatCompletionsCodec.encodeRequest(request).toString(), ContentType.Application.Json))
                        }
                    val body = response.bodyAsText()
                    if (response.status.isSuccess()) {
                        ChatCompletionsCodec.decodeResponse(body) { "call_${toolCallIds.incrementAndGet()}" }
                    } else {
                        listOf(httpError(response, body))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listOf(ModelErrors.network(e))
                }
            for (event in events) emit(event)
        }

    private fun HttpRequestBuilder.configure() {
        // Failures are reported as events, whatever the shared client's default is.
        expectSuccess = false
        if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        for ((name, value) in extraHeaders) header(name, value)
    }

    private fun httpError(
        response: HttpResponse,
        body: String,
    ): ModelEvent.Error = ModelErrors.fromHttp(response.status.value, body, response.headers[HttpHeaders.RetryAfter])

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
