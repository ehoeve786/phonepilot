package app.pocketpilot.agent.providers.anthropic

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.agent.providers.api.ToolChoice
import app.pocketpilot.agent.providers.api.ToolDef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicProviderTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<JsonObject>()

    private fun provider(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AnthropicProvider(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val bytes = request.body.toByteArray()
                    if (bytes.isNotEmpty()) bodies += Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    reply(request)
                },
            ),
            apiKey = "sk-test",
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        vararg headers: Pair<String, String>,
    ) = respond(
        body,
        status,
        headersOf(
            *(headers.map { it.first to listOf(it.second) } + (HttpHeaders.ContentType to listOf("application/json"))).toTypedArray(),
        ),
    )

    private val textReply =
        """{"content":[{"type":"text","text":"Done."}],"stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":3}}"""

    @Test
    fun `sends the conversation in the Messages API shape`() =
        runTest {
            val request =
                ModelRequest(
                    model = "claude-test",
                    system = "Be brief.",
                    messages =
                        listOf(
                            Message.user("Open settings"),
                            Message(Role.USER, listOf(Part.Image(byteArrayOf(1, 2, 3), "image/png"))),
                            Message(Role.ASSISTANT, listOf(Part.ToolCall("toolu_1", "tap", """{"x":4}"""))),
                            Message(
                                Role.USER,
                                listOf(
                                    Part.Text("after"),
                                    Part.ToolResult(
                                        "toolu_1",
                                        "tap",
                                        listOf(Part.Text("ok"), Part.Image(byteArrayOf(9), "image/jpeg")),
                                        isError = true,
                                    ),
                                ),
                            ),
                        ),
                    tools = listOf(ToolDef("tap", "Taps", Json.parseToJsonElement("""{"type":"object"}""").jsonObject)),
                    maxTokens = 100,
                    temperature = 0.5,
                    toolChoice = ToolChoice.ANY,
                )
            provider { json(textReply) }.stream(request).toList()

            val headers = requests.single().headers
            assertEquals("sk-test", headers["x-api-key"])
            assertEquals("2023-06-01", headers["anthropic-version"])
            assertEquals("https://api.anthropic.com/v1/messages", requests.single().url.toString())
            val expected =
                """
                {"model":"claude-test","max_tokens":100,"system":"Be brief.","temperature":0.5,
                 "messages":[
                  {"role":"user","content":[{"type":"text","text":"Open settings"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AQID"}}]},
                  {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"tap","input":{"x":4}}]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"ok"},
                      {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"CQ=="}}],"is_error":true},
                    {"type":"text","text":"after"}]}],
                 "tools":[{"name":"tap","description":"Taps","input_schema":{"type":"object"}}],
                 "tool_choice":{"type":"any"}}
                """.trimIndent()
            assertEquals(Json.parseToJsonElement(expected), bodies.single())
        }

    @Test
    fun `parses a tool use reply`() =
        runTest {
            val reply =
                """
                {"content":[{"type":"text","text":"Tapping."},{"type":"tool_use","id":"toolu_9","name":"tap","input":{"x":1}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":20}}
                """.trimIndent()
            val events = provider { json(reply) }.stream(ModelRequest("m", "", listOf(Message.user("hi")))).toList()

            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Tapping."),
                    ModelEvent.ToolCall("toolu_9", "tap", """{"x":1}"""),
                    ModelEvent.Usage(50, 20),
                    ModelEvent.Stop(StopReason.TOOL_USE),
                ),
                events,
            )
        }

    @Test
    fun `parses a text reply`() =
        runTest {
            val events = provider { json(textReply) }.stream(ModelRequest("m", "", listOf(Message.user("hi")))).toList()

            assertEquals(listOf(ModelEvent.TextDelta("Done."), ModelEvent.Usage(12, 3), ModelEvent.Stop(StopReason.END_TURN)), events)
        }

    @Test
    fun `maps a 401 to an auth error`() =
        runTest {
            val events =
                provider { json("""{"error":{"message":"invalid x-api-key"}}""", HttpStatusCode.Unauthorized) }
                    .stream(ModelRequest("m", "", listOf(Message.user("hi"))))
                    .toList()

            assertEquals(ModelErrorCode.AUTH, (events.single() as ModelEvent.Error).code)
        }

    @Test
    fun `maps a 429 with retry-after to a rate limit`() =
        runTest {
            val events =
                provider { json("""{"error":{}}""", HttpStatusCode.TooManyRequests, "retry-after" to "7") }
                    .stream(ModelRequest("m", "", listOf(Message.user("hi"))))
                    .toList()

            val error = events.single() as ModelEvent.Error
            assertEquals(ModelErrorCode.RATE_LIMIT, error.code)
            assertEquals(7000L, error.retryAfterMs)
        }

    @Test
    fun `turns a thrown IOException into a network error`() =
        runTest {
            val events = provider { throw IOException("reset") }.stream(ModelRequest("m", "", listOf(Message.user("hi")))).toList()

            assertEquals(ModelEvent.Error(ModelErrorCode.NETWORK, "reset"), events.single())
        }

    @Test
    fun `lists models across pages`() =
        runTest {
            val models =
                provider { request ->
                    if (request.url.parameters["after_id"] == null) {
                        json("""{"data":[{"id":"a","display_name":"A"}],"has_more":true,"last_id":"a"}""")
                    } else {
                        json("""{"data":[{"id":"b","display_name":"B"}],"has_more":false,"last_id":"b"}""")
                    }
                }.listModels()

            assertEquals(listOf(ModelInfo("a", "A"), ModelInfo("b", "B")), models)
            assertEquals("sk-test", requests.first().headers["x-api-key"])
        }
}
