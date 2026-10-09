package app.pocketpilot.server.http

import app.pocketpilot.capability.api.DeviceInfo
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.tools.DeviceInfoTool
import app.pocketpilot.server.mcp.McpServerFactory
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.server.oauth.InMemoryOAuthStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The remote port as Claude reaches it through the Tailscale forwarder: discovery from a 401, dynamic
 * registration, consent on the phone, PKCE code exchange, then MCP with the access token.
 */
class RemoteOAuthTest {
    private val host = "pocketpilot-test.tail1234.ts.net"
    private val redirect = "https://claude.ai/api/mcp/auth_callback"
    private val oauth = AuthorizationServer(InMemoryOAuthStore())
    private lateinit var server: McpHttpServer
    private var remotePort = 0

    private val deviceInfo =
        DeviceInfo(
            manufacturer = "Samsung",
            model = "SM-S918W",
            androidVersion = "16",
            sdkInt = 36,
            screen = DeviceInfo.Screen(1440, 3088, 500),
            battery = DeviceInfo.Battery(levelPercent = 80, charging = true),
            network = DeviceInfo.Network(connected = true, transport = "wifi"),
        )

    @BeforeTest
    fun start() {
        val registry = ToolRegistry(setOf(DeviceInfoTool { deviceInfo }), PolicyProfile.OSS)
        val factory = McpServerFactory(registry, CallDispatcher(registry, InMemoryAuditSink()), appVersion = "0.1.0-test")
        server =
            McpHttpServer(
                factory,
                SessionFactory(),
                localToken = { "local-token" },
                oauth = oauth,
                remoteHosts = { setOf(host) },
            )
        server.start(port = 0, remotePort = 0)
        remotePort = assertIs<ServerState.Running>(server.serverState.value).remotePort
    }

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun `claude connects through discovery, registration, consent and PKCE`() {
        // 1. An unauthenticated MCP request points to the protected resource metadata.
        val first = request("POST", "/mcp", body = INITIALIZE, contentType = "application/json")
        assertEquals(401, first.status)
        val challenge = assertNotNull(first.headers["www-authenticate"])
        assertTrue("resource_metadata=\"https://$host/.well-known/oauth-protected-resource\"" in challenge, challenge)

        val resource = Json.parseToJsonElement(request("GET", "/.well-known/oauth-protected-resource").body).jsonObject
        assertEquals("https://$host/mcp", resource.string("resource"))
        assertEquals(
            "https://$host",
            resource
                .getValue("authorization_servers")
                .jsonArray[0]
                .jsonPrimitive.content,
        )
        val metadata = Json.parseToJsonElement(request("GET", "/.well-known/oauth-authorization-server").body).jsonObject
        assertEquals("https://$host/token", metadata.string("token_endpoint"))

        // 2. Dynamic registration.
        val registered =
            request(
                "POST",
                "/register",
                body = """{"client_name":"Claude","redirect_uris":["$redirect"]}""",
                contentType = "application/json",
            )
        assertEquals(201, registered.status, registered.body)
        val clientId = Json.parseToJsonElement(registered.body).jsonObject.string("client_id")

        // 3. The browser opens /authorize and waits while the owner approves on the phone.
        val verifier = "v".repeat(20) + "erifier-with-enough-entropy-1234567890"
        val challengeValue =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val authorize =
            request(
                "GET",
                "/authorize?response_type=code&client_id=$clientId&redirect_uri=${enc(redirect)}" +
                    "&code_challenge=$challengeValue&code_challenge_method=S256&state=xyz&scope=device%3Aread",
            )
        assertEquals(200, authorize.status, authorize.body)
        val pending = oauth.pending.value.single()
        assertEquals("Claude", pending.clientName)
        assertEquals("claude.ai", pending.redirectHost)
        assertEquals(setOf(Scope.DEVICE_READ), pending.requestedScopes)
        oauth.approve(pending.id, setOf(Scope.DEVICE_READ))

        val back = request("GET", "/authorize/wait?request=${pending.id}")
        assertEquals(302, back.status)
        val location = assertNotNull(back.headers["location"])
        assertTrue(location.startsWith("$redirect?code="), location)
        assertTrue(location.endsWith("&state=xyz"), location)
        val code = location.substringAfter("code=").substringBefore('&')

        // 4. Code exchange with PKCE.
        val token =
            request(
                "POST",
                "/token",
                body =
                    "grant_type=authorization_code&code=$code&client_id=$clientId&redirect_uri=${enc(redirect)}" +
                        "&code_verifier=$verifier",
                contentType = "application/x-www-form-urlencoded",
            )
        assertEquals(200, token.status, token.body)
        val accessToken = Json.parseToJsonElement(token.body).jsonObject.string("access_token")

        // 5. MCP with the token.
        val init = request("POST", "/mcp", body = INITIALIZE, contentType = "application/json", bearer = accessToken)
        assertEquals(200, init.status, init.body)
        val sessionId = assertNotNull(init.headers["mcp-session-id"])
        request(
            "POST",
            "/mcp",
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            bearer = accessToken,
            sessionId = sessionId,
        )
        val call =
            request(
                "POST",
                "/mcp",
                body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"device.info","arguments":{}}}""",
                contentType = "application/json",
                bearer = accessToken,
                sessionId = sessionId,
            )
        assertEquals(200, call.status, call.body)
        assertTrue("SM-S918W" in call.body, call.body)

        // 6. Revoking the client ends access at once.
        oauth.revokeClient(clientId)
        assertEquals(401, request("POST", "/mcp", body = INITIALIZE, contentType = "application/json", bearer = accessToken).status)
    }

    @Test
    fun `the local token does not work from outside`() {
        val response = request("POST", "/mcp", body = INITIALIZE, contentType = "application/json", bearer = "local-token")
        assertEquals(401, response.status)
    }

    @Test
    fun `unknown host names are refused on the remote port`() {
        assertEquals(403, request("GET", "/.well-known/oauth-protected-resource", hostHeader = "evil.example").status)
    }

    private class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    /** A minimal HTTP/1.1 client, since java.net.http does not let tests set the Host header. */
    private fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String? = null,
        bearer: String? = null,
        sessionId: String? = null,
        hostHeader: String = host,
    ): Response =
        Socket("127.0.0.1", remotePort).use { socket ->
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head =
                buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: $hostHeader\r\n")
                    append("X-Forwarded-For: 203.0.113.7\r\n")
                    append("Accept: application/json, text/event-stream\r\n")
                    append("Connection: close\r\n")
                    contentType?.let { append("Content-Type: $it\r\n") }
                    bearer?.let { append("Authorization: Bearer $it\r\n") }
                    sessionId?.let { append("Mcp-Session-Id: $it\r\n") }
                    append("Content-Length: ${bytes.size}\r\n\r\n")
                }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
            val raw = socket.getInputStream().readBytes().decodeToString()
            val headerText = raw.substringBefore("\r\n\r\n")
            var payload = raw.substringAfter("\r\n\r\n", "")
            val lines = headerText.split("\r\n")
            val headers =
                lines.drop(1).associate { line ->
                    line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
                }
            if (headers["transfer-encoding"]?.contains("chunked") == true) payload = dechunk(payload)
            Response(lines.first().split(' ')[1].toInt(), headers, payload)
        }

    private fun dechunk(text: String): String {
        val out = StringBuilder()
        var rest = text
        while (rest.isNotEmpty()) {
            val size = rest.substringBefore("\r\n").trim().toIntOrNull(HEX) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            out.append(rest, 0, size)
            rest = rest.substring(size).removePrefix("\r\n")
        }
        return out.toString()
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val HEX = 16
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                """"clientInfo":{"name":"claude-ai","version":"1"}}}"""
    }
}
