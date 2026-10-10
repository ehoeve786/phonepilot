package app.pocketpilot.agent.providers.openai

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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiProviderTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun provider(
        apiKey: String? = "sk-test",
        baseUrl: String = "https://api.openai.com/v1",
        reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { ok(TEXT_REPLY) },
    ) = OpenAiProvider(
        HttpClient(
            MockEngine { request ->
                requests += request
                reply(request)
            },
        ),
        apiKey,
        baseUrl,
    )

    private fun sentBody(): JsonObject = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject

    @Test
    fun postsToChatCompletionsWithBearerKey() =
        runTest {
            provider(baseUrl = "https://api.openai.com/v1/").stream(simpleRequest()).toList()
            val request = requests.single()
            assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
            assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
            val body = sentBody()
            assertEquals("gpt-4.1-mini", body["model"]!!.jsonPrimitive.content)
            assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble())
            val messages = body["messages"]!!.jsonArray
            assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("Be brief.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        }

    @Test
    fun omitsAuthorizationWithoutKey() =
        runTest {
            provider(apiKey = null).stream(simpleRequest()).toList()
            assertNull(requests.single().headers[HttpHeaders.Authorization])
            requests.clear()
            provider(apiKey = " ").stream(simpleRequest()).toList()
            assertNull(requests.single().headers[HttpHeaders.Authorization])
        }

    @Test
    fun sendsImagesAsDataUrls() =
        runTest {
            val image = Part.Image(byteArrayOf(1, 2, 3), "image/png")
            provider().stream(simpleRequest(Message(Role.USER, listOf(Part.Text("What is this?"), image)))).toList()
            val content = sentBody()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("What is this?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}",
                content[1]
                    .jsonObject["image_url"]!!
                    .jsonObject["url"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun sendsToolCallsAndResultsAsToolMessages() =
        runTest {
            val screenshot = Part.Image(byteArrayOf(9), "image/jpeg")
            val conversation =
                listOf(
                    Message.user("Open settings"),
                    Message(
                        Role.ASSISTANT,
                        listOf(
                            Part.Text("Looking."),
                            Part.ToolCall("call_a", "screenshot", "{}"),
                            Part.ToolCall("call_b", "tap", """{"x":1}"""),
                        ),
                    ),
                    Message(
                        Role.USER,
                        listOf(
                            Part.ToolResult("call_a", "screenshot", listOf(screenshot)),
                            Part.ToolResult("call_b", "tap", listOf(Part.Text("No such element")), isError = true),
                        ),
                    ),
                )
            provider().stream(simpleRequest(*conversation.toTypedArray())).toList()
            val messages = sentBody()["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), messages.map { it.role })

            val assistant = messages[2]
            assertEquals("Looking.", assistant["content"]!!.jsonPrimitive.content)
            val calls = assistant["tool_calls"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("call_a", "call_b"), calls.map { it["id"]!!.jsonPrimitive.content })
            assertEquals("function", calls[1]["type"]!!.jsonPrimitive.content)
            assertEquals("tap", calls[1]["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("""{"x":1}""", calls[1]["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)

            assertEquals("call_a", messages[3]["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue("image" in messages[3]["content"]!!.jsonPrimitive.content)
            assertEquals("call_b", messages[4]["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("Error: No such element", messages[4]["content"]!!.jsonPrimitive.content)

            val followUp = messages[5]["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(ChatCompletionsCodec.TOOL_IMAGES_CAPTION, followUp[0]["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", followUp[1]["type"]!!.jsonPrimitive.content)
            assertTrue(
                followUp[1]["image_url"]!!
                    .jsonObject["url"]!!
                    .jsonPrimitive.content
                    .startsWith("data:image/jpeg;base64,"),
            )
            assertEquals(2, followUp.size)
        }

    @Test
    fun toolResultsWithoutImagesNeedNoUserMessage() =
        runTest {
            val conversation =
                listOf(
                    Message.user("Go"),
                    Message(Role.ASSISTANT, listOf(Part.ToolCall("call_a", "home", "{}"))),
                    Message(Role.USER, listOf(Part.ToolResult("call_a", "home", listOf(Part.Text("ok"))))),
                )
            provider().stream(simpleRequest(*conversation.toTypedArray())).toList()
            val messages = sentBody()["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.role })
            assertNull(messages[2]["content"])
        }

    @Test
    fun sendsToolsAndToolChoice() =
        runTest {
            val schema = buildJsonObject { put("type", "object") }
            val request =
                simpleRequest().copy(
                    tools = listOf(ToolDef("tap", "Tap the screen", schema)),
                    toolChoice = ToolChoice.ANY,
                )
            provider().stream(request).toList()
            val body = sentBody()
            val tool = body["tools"]!!.jsonArray.single().jsonObject
            assertEquals("function", tool["type"]!!.jsonPrimitive.content)
            val function = tool["function"]!!.jsonObject
            assertEquals("tap", function["name"]!!.jsonPrimitive.content)
            assertEquals("Tap the screen", function["description"]!!.jsonPrimitive.content)
            assertEquals(schema, function["parameters"])
            assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)

            requests.clear()
            provider().stream(request.copy(toolChoice = ToolChoice.NONE)).toList()
            assertEquals("none", sentBody()["tool_choice"]!!.jsonPrimitive.content)
            requests.clear()
            provider().stream(request.copy(toolChoice = null)).toList()
            assertNull(sentBody()["tool_choice"])
        }

    @Test
    fun parsesToolCallReplyAndFillsMissingIds() =
        runTest {
            val events = provider { ok(TOOL_REPLY) }.stream(simpleRequest()).toList()
            assertEquals(
                listOf(
                    ModelEvent.TextDelta("Tapping."),
                    ModelEvent.ToolCall("call_x", "tap", """{"x":10}"""),
                    ModelEvent.ToolCall("call_1", "screenshot", "{}"),
                    ModelEvent.Usage(120, 30),
                    ModelEvent.Stop(StopReason.TOOL_USE),
                ),
                events,
            )
        }

    @Test
    fun parsesTextReply() =
        runTest {
            val events = provider().stream(simpleRequest()).toList()
            assertEquals(
                listOf(ModelEvent.TextDelta("Hi there"), ModelEvent.Usage(10, 3), ModelEvent.Stop(StopReason.END_TURN)),
                events,
            )
        }

    @Test
    fun lengthStopsWithMaxTokens() =
        runTest {
            val reply = """{"choices":[{"message":{"content":"Hi"},"finish_reason":"length"}]}"""
            val events = provider { ok(reply) }.stream(simpleRequest()).toList()
            assertEquals(ModelEvent.Stop(StopReason.MAX_TOKENS), events.last())
        }

    @Test
    fun unauthorizedIsAuthError() =
        runTest {
            val events =
                provider { respond("""{"error":{"message":"bad key"}}""", HttpStatusCode.Unauthorized) }
                    .stream(simpleRequest())
                    .toList()
            val error = events.single() as ModelEvent.Error
            assertEquals(ModelErrorCode.AUTH, error.code)
            assertTrue("bad key" in error.message)
        }

    @Test
    fun rateLimitCarriesRetryAfter() =
        runTest {
            val events =
                provider {
                    respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
                }.stream(simpleRequest()).toList()
            val error = events.single() as ModelEvent.Error
            assertEquals(ModelErrorCode.RATE_LIMIT, error.code)
            assertEquals(7000L, error.retryAfterMs)
        }

    @Test
    fun ioFailureIsNetworkError() =
        runTest {
            val events = provider { throw IOException("connection refused") }.stream(simpleRequest()).toList()
            val error = events.single() as ModelEvent.Error
            assertEquals(ModelErrorCode.NETWORK, error.code)
            assertEquals("connection refused", error.message)
        }

    @Test
    fun malformedReplyIsSingleError() =
        runTest {
            val events = provider { ok("<html>") }.stream(simpleRequest()).toList()
            assertEquals(ModelErrorCode.SERVER, (events.single() as ModelEvent.Error).code)
        }

    @Test
    fun listsModels() =
        runTest {
            val models = provider { ok("""{"object":"list","data":[{"id":"gpt-4.1-mini"},{"id":"gpt-4.1"}]}""") }.listModels()
            assertEquals("https://api.openai.com/v1/models", requests.single().url.toString())
            assertEquals(listOf(ModelInfo("gpt-4.1-mini"), ModelInfo("gpt-4.1")), models)
        }

    private val JsonObject.role get() = this["role"]!!.jsonPrimitive.content

    private fun simpleRequest(vararg messages: Message) =
        ModelRequest(
            model = "gpt-4.1-mini",
            system = "Be brief.",
            messages = messages.toList().ifEmpty { listOf(Message.user("Hello")) },
            maxTokens = 1024,
            temperature = 0.2,
        )

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        val TEXT_REPLY =
            """
            {"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
            """.trimIndent()

        // The second call has no id, as Ollama sends them; Ollama also reports "stop" here.
        val TOOL_REPLY =
            """
            {"choices":[{"message":{"role":"assistant","content":"Tapping.","tool_calls":[
            {"id":"call_x","type":"function","function":{"name":"tap","arguments":"{\"x\":10}"}},
            {"type":"function","function":{"name":"screenshot","arguments":{}}}]},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}
            """.trimIndent()
    }
}
