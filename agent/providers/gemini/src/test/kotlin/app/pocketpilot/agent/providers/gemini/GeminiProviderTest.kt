package app.pocketpilot.agent.providers.gemini

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiProviderTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<JsonObject>()

    private fun provider(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GeminiProvider(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val bytes = request.body.toByteArray()
                    if (bytes.isNotEmpty()) bodies += Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    reply(request)
                },
            ),
            apiKey = "g-key",
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

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private val hi = ModelRequest("gemini-test", "", listOf(Message.user("hi")))

    private val toolReply =
        """
        {"candidates":[{"content":{"role":"model","parts":[
          {"text":"thinking...","thought":true},
          {"text":"Tapping."},
          {"functionCall":{"name":"tap","args":{"x":1}},"thoughtSignature":"sig-1"}]},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":10,"thoughtsTokenCount":5}}
        """.trimIndent()

    @Test
    fun `sends the conversation in the generateContent shape`() =
        runTest {
            val request =
                ModelRequest(
                    model = "gemini-test",
                    system = "Be brief.",
                    messages =
                        listOf(
                            Message.user("Open settings"),
                            Message(Role.USER, listOf(Part.Image(byteArrayOf(1, 2, 3), "image/png"))),
                            Message(Role.ASSISTANT, listOf(Part.ToolCall("call_1", "tap", """{"x":4}"""))),
                            Message(
                                Role.USER,
                                listOf(
                                    Part.ToolResult(
                                        "call_1",
                                        "tap",
                                        listOf(Part.Text("ok"), Part.Image(byteArrayOf(9), "image/jpeg")),
                                        isError = true,
                                    ),
                                ),
                            ),
                        ),
                    tools =
                        listOf(
                            ToolDef("tap", "Taps", parse("""{"type":"object","properties":{"x":{"type":"integer"}}}""").jsonObject),
                            ToolDef("back", "Goes back", parse("""{"type":"object","properties":{}}""").jsonObject),
                        ),
                    maxTokens = 100,
                    temperature = 0.5,
                    toolChoice = ToolChoice.ANY,
                )
            provider { json("""{"candidates":[]}""") }.stream(request).toList()

            assertEquals("g-key", requests.single().headers["x-goog-api-key"])
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
                requests.single().url.toString(),
            )
            val expected =
                """
                {"systemInstruction":{"parts":[{"text":"Be brief."}]},
                 "contents":[
                  {"role":"user","parts":[{"text":"Open settings"},{"inlineData":{"mimeType":"image/png","data":"AQID"}}]},
                  {"role":"model","parts":[{"functionCall":{"name":"tap","args":{"x":4}}}]},
                  {"role":"user","parts":[
                    {"functionResponse":{"name":"tap","response":{"content":"ok","isError":true}}},
                    {"inlineData":{"mimeType":"image/jpeg","data":"CQ=="}}]}],
                 "tools":[{"functionDeclarations":[
                   {"name":"tap","description":"Taps","parameters":{"type":"object","properties":{"x":{"type":"integer"}}}},
                   {"name":"back","description":"Goes back"}]}],
                 "toolConfig":{"functionCallingConfig":{"mode":"ANY"}},
                 "generationConfig":{"maxOutputTokens":100,"temperature":0.5}}
                """.trimIndent()
            assertEquals(parse(expected), bodies.single())
        }

    @Test
    fun `parses a function call reply and echoes its thought signature`() =
        runTest {
            val provider = provider { json(toolReply) }
            val events = provider.stream(hi).toList()

            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Tapping."),
                    ModelEvent.ToolCall("call_0", "tap", """{"x":1}"""),
                    ModelEvent.Usage(40, 15),
                    ModelEvent.Stop(StopReason.TOOL_USE),
                ),
                events,
            )

            val followUp = hi.copy(messages = hi.messages + Message(Role.ASSISTANT, listOf(Part.ToolCall("call_0", "tap", """{"x":1}"""))))
            provider.stream(followUp).toList()
            val echoed =
                bodies
                    .last()["contents"]!!
                    .jsonArray[1]
                    .jsonObject["parts"]!!
                    .jsonArray[0]
            assertEquals(parse("""{"functionCall":{"name":"tap","args":{"x":1}},"thoughtSignature":"sig-1"}"""), echoed)
        }

    @Test
    fun `parses a text reply`() =
        runTest {
            val reply =
                """
                {"candidates":[{"content":{"parts":[{"text":"Done."}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3}}
                """.trimIndent()
            val events = provider { json(reply) }.stream(hi).toList()

            assertEquals(listOf(ModelEvent.TextDelta("Done."), ModelEvent.Usage(12, 3), ModelEvent.Stop(StopReason.END_TURN)), events)
        }

    @Test
    fun `maps auth failures to an auth error`() =
        runTest {
            val unauthorized = provider { json("""{"error":{}}""", HttpStatusCode.Unauthorized) }.stream(hi).toList()
            val badKey =
                provider {
                    json(
                        """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""",
                        HttpStatusCode.BadRequest,
                    )
                }.stream(hi)
                    .toList()

            assertEquals(ModelErrorCode.AUTH, (unauthorized.single() as ModelEvent.Error).code)
            assertEquals(ModelErrorCode.AUTH, (badKey.single() as ModelEvent.Error).code)
        }

    @Test
    fun `maps a 429 to a rate limit with the wait from the header or the body`() =
        runTest {
            val header = provider { json("""{"error":{}}""", HttpStatusCode.TooManyRequests, "retry-after" to "7") }.stream(hi).toList()
            val body =
                provider { json("""{"error":{"details":[{"retryDelay": "12s"}]}}""", HttpStatusCode.TooManyRequests) }
                    .stream(hi)
                    .toList()

            assertEquals(ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "HTTP 429: {\"error\":{}}", 7000L), header.single())
            assertEquals(12000L, (body.single() as ModelEvent.Error).retryAfterMs)
        }

    @Test
    fun `turns a thrown IOException into a network error`() =
        runTest {
            val events = provider { throw IOException("reset") }.stream(hi).toList()

            assertEquals(ModelEvent.Error(ModelErrorCode.NETWORK, "reset"), events.single())
        }

    @Test
    fun `lists generateContent models without the models prefix`() =
        runTest {
            val reply =
                """
                {"models":[
                  {"name":"models/gemini-pro","displayName":"Gemini Pro","supportedGenerationMethods":["generateContent","countTokens"]},
                  {"name":"models/embedding","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}]}
                """.trimIndent()
            val models = provider { json(reply) }.listModels()

            assertEquals(listOf(ModelInfo("gemini-pro", "Gemini Pro")), models)
            assertEquals("g-key", requests.single().headers["x-goog-api-key"])
        }
}
