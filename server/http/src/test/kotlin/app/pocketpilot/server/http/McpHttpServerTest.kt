package app.pocketpilot.server.http

import app.pocketpilot.capability.api.DeviceInfo
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.tools.DeviceInfoTool
import app.pocketpilot.server.mcp.McpServerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives the real loopback server the way an MCP client such as Claude Code does. */
class McpHttpServerTest {
    private val token = "test-token-123"
    private val audit = InMemoryAuditSink()
    private lateinit var server: McpHttpServer
    private lateinit var url: String
    private val http = HttpClient.newHttpClient()

    private val deviceInfo =
        DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 9",
            androidVersion = "16",
            sdkInt = 36,
            screen = DeviceInfo.Screen(1080, 2424, 420),
            battery = DeviceInfo.Battery(levelPercent = 64, charging = false),
            network = DeviceInfo.Network(connected = true, transport = "wifi"),
        )

    @BeforeTest
    fun start() {
        val registry = ToolRegistry(setOf(DeviceInfoTool { deviceInfo }), PolicyProfile.OSS)
        val factory = McpServerFactory(registry, CallDispatcher(registry, audit), appVersion = "0.1.0-test")
        server = McpHttpServer(factory, SessionFactory(), localToken = { token })
        server.start(port = 0)
        val running = assertIs<ServerState.Running>(server.serverState.value)
        url = running.url
    }

    @AfterTest
    fun stop() = server.stop()

    private fun post(
        body: String,
        sessionId: String? = null,
        bearer: String? = token,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun initialize(): String {
        val response =
            post(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                |"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """.trimMargin(),
            )
        assertEquals(200, response.statusCode(), response.body())
        val sessionId = assertNotNull(response.headers().firstValue("mcp-session-id").orElse(null))
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", sessionId)
        return sessionId
    }

    private fun result(response: HttpResponse<String>): JsonObject {
        assertEquals(200, response.statusCode(), response.body())
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("result")
            .jsonObject
    }

    @Test
    fun `binds to loopback only`() {
        assertTrue(url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `rejects requests without the local token`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer = null).statusCode())
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer = "wrong").statusCode())
    }

    @Test
    fun `initialize reports the pocketpilot server and its instructions`() {
        val response =
            post(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                |"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """.trimMargin(),
            )
        val init = result(response)
        assertEquals(
            "pocketpilot",
            init
                .getValue("serverInfo")
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
        assertTrue(
            init
                .getValue("instructions")
                .jsonPrimitive.content
                .contains("device.info"),
        )
    }

    @Test
    fun `lists tools and calls device info`() {
        val sessionId = initialize()

        val tools = result(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", sessionId))
        val names =
            tools.getValue("tools").jsonArray.map {
                it.jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            }
        assertEquals(listOf("device.info"), names)

        val call =
            result(
                post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"device.info","arguments":{}}}""", sessionId),
            )
        val text =
            call
                .getValue("content")
                .jsonArray[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content
        assertTrue(text.contains("Pixel 9"), text)
        assertEquals(
            "Pixel 9",
            call
                .getValue("structuredContent")
                .jsonObject
                .getValue("model")
                .jsonPrimitive.content,
        )
        assertEquals(
            "device.info",
            audit.recent.value
                .single()
                .tool,
        )
    }

    @Test
    fun `bad arguments come back as a tool error with a code`() {
        val sessionId = initialize()
        val call =
            result(
                post(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"device.info","arguments":{"x":1}}}""",
                    sessionId,
                ),
            )
        assertEquals(true, call["isError"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            "INVALID_ARGUMENTS",
            call
                .getValue("structuredContent")
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content,
        )
    }
}
