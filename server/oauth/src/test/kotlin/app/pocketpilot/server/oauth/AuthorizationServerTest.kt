package app.pocketpilot.server.oauth

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Scope
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizationServerTest {
    private var now = 1_000_000L
    private val store = InMemoryOAuthStore()
    private val server = AuthorizationServer(store, Clock { now })
    private val redirect = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "a-code-verifier-that-is-long-enough-for-pkce-0123456789"

    private fun challenge(v: String = verifier) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))

    private fun authorize(
        clientId: String,
        scopes: Set<Scope> = setOf(Scope.SCREEN_READ),
    ): String {
        val id =
            server.beginAuthorization(
                mapOf(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to redirect,
                    "code_challenge" to challenge(),
                    "code_challenge_method" to "S256",
                    "state" to "s1",
                ),
            )
        pairWithPhoneCode(id)
        server.approve(id, scopes)
        val status = assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id))
        return status.url.substringAfter("code=").substringBefore('&')
    }

    /** The browser types the code the phone shows for this request. */
    private fun pairWithPhoneCode(id: String) {
        val code =
            server.pending.value
                .first { it.id == id }
                .pairingCode
        assertTrue(server.pair(id, code))
    }

    private fun beginFor(clientId: String) =
        server.beginAuthorization(
            mapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirect,
                "code_challenge" to challenge(),
                "code_challenge_method" to "S256",
            ),
        )

    private fun exchange(
        clientId: String,
        code: String,
        v: String = verifier,
    ) = server.token(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId,
            "redirect_uri" to redirect,
            "code_verifier" to v,
        ),
    )

    @Test
    fun `issues tokens that carry the approved scopes`() {
        val client = server.register("Claude", listOf(redirect))
        val tokens = exchange(client.id, authorize(client.id, setOf(Scope.SCREEN_READ, Scope.UI_INTERACT)))
        val grant = assertNotNull(server.authenticate(tokens.accessToken))
        assertEquals(setOf(Scope.SCREEN_READ, Scope.UI_INTERACT), grant.scopes)
        assertEquals("Claude (claude.ai)", grant.clientName)
        assertEquals("screen:read ui:interact", tokens.scope)
    }

    @Test
    fun `codes are single use and need the right verifier`() {
        val client = server.register("Claude", listOf(redirect))
        val code = authorize(client.id)
        assertFailsWith<OAuthException> { exchange(client.id, code, v = verifier.reversed()) }
        // A failed exchange still consumes the code.
        assertFailsWith<OAuthException> { exchange(client.id, code) }
    }

    @Test
    fun `refresh tokens rotate and reuse revokes the grant`() {
        val client = server.register("Claude", listOf(redirect))
        val first = exchange(client.id, authorize(client.id))
        val second = server.token(mapOf("grant_type" to "refresh_token", "refresh_token" to first.refreshToken))
        assertNotNull(server.authenticate(second.accessToken))

        val reuse =
            assertFailsWith<OAuthException> {
                server.token(mapOf("grant_type" to "refresh_token", "refresh_token" to first.refreshToken))
            }
        assertEquals("invalid_grant", reuse.code)
        assertNull(server.authenticate(second.accessToken))
    }

    @Test
    fun `access tokens expire after an hour`() {
        val client = server.register("Claude", listOf(redirect))
        val tokens = exchange(client.id, authorize(client.id))
        now += AuthorizationServer.ACCESS_LIFETIME_MS + 1
        assertNull(server.authenticate(tokens.accessToken))
    }

    @Test
    fun `an unregistered redirect uri is refused without redirecting`() {
        val client = server.register("Claude", listOf(redirect))
        assertFailsWith<OAuthException> {
            server.beginAuthorization(mapOf("client_id" to client.id, "redirect_uri" to "https://evil.example/cb"))
        }
    }

    @Test
    fun `missing pkce redirects back with an error`() {
        val client = server.register("Claude", listOf(redirect))
        val id = server.beginAuthorization(mapOf("response_type" to "code", "client_id" to client.id, "redirect_uri" to redirect))
        val status = assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id))
        assertTrue("error=invalid_request" in status.url, status.url)
        assertTrue(server.pending.value.isEmpty())
    }

    @Test
    fun `denying redirects with access_denied`() {
        val client = server.register("Claude", listOf(redirect))
        val id =
            server.beginAuthorization(
                mapOf(
                    "response_type" to "code",
                    "client_id" to client.id,
                    "redirect_uri" to redirect,
                    "code_challenge" to challenge(),
                    "code_challenge_method" to "S256",
                ),
            )
        assertEquals(1, server.pending.value.size)
        server.deny(id)
        val status = assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id))
        assertTrue("error=access_denied" in status.url, status.url)
        assertTrue(server.pending.value.isEmpty())
    }

    private fun begin(clientId: String) =
        server.beginAuthorization(
            mapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirect,
                "code_challenge" to challenge(),
                "code_challenge_method" to "S256",
            ),
        )

    @Test
    fun `a client has one prompt at a time and few can wait at once`() {
        val claude = server.register("Claude", listOf(redirect))
        val first = begin(claude.id)
        begin(claude.id)
        assertEquals(1, server.pending.value.size)
        assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(first))
        begin(server.register("b", listOf(redirect)).id)
        begin(server.register("c", listOf(redirect)).id)
        val refused = assertFailsWith<OAuthException> { begin(server.register("d", listOf(redirect)).id) }
        assertEquals(429, refused.status)
    }

    @Test
    fun `client names are one clean line and confirmations name the sign-in host`() {
        val client = server.register("Claude\nApproved by Erik\u202e", listOf(redirect))
        assertEquals("Claude Approved by Erik", client.name)
        val code =
            server
                .beginAuthorization(
                    mapOf(
                        "response_type" to "code",
                        "client_id" to client.id,
                        "code_challenge" to challenge(),
                        "code_challenge_method" to "S256",
                    ),
                ).let { id ->
                    pairWithPhoneCode(id)
                    server.approve(id, setOf(Scope.SCREEN_READ))
                    assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id)).url.substringAfter("code=").substringBefore('&')
                }
        val tokens =
            server.token(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "client_id" to client.id,
                    "redirect_uri" to redirect,
                    "code_verifier" to verifier,
                ),
            )
        assertEquals("Claude Approved by Erik (claude.ai)", server.authenticate(tokens.accessToken)?.clientName)
    }

    @Test
    fun `only Claude's callback or loopback redirects can register`() {
        assertFailsWith<OAuthException> { server.register("x", listOf("http://example.com/cb")) }
        assertFailsWith<OAuthException> { server.register("x", listOf("myapp://cb")) }
        assertFailsWith<OAuthException> { server.register("x", listOf("https://evil.example/cb")) }
        assertFailsWith<OAuthException> { server.register("x", listOf("https://claude.ai.evil.example/api/mcp/auth_callback")) }
        assertFailsWith<OAuthException> { server.register("x", listOf("https://claude.ai/some/other/path")) }
        server.register("x", listOf("http://127.0.0.1:3000/cb"))
        server.register("x", listOf("https://claude.com/api/mcp/auth_callback"))
    }

    @Test
    fun `the phone cannot approve before the browser sends the code shown on it`() {
        val client = server.register("Claude", listOf(redirect))
        val id = beginFor(client.id)
        server.approve(id, setOf(Scope.SCREEN_READ))
        assertIs<AuthorizationStatus.Waiting>(server.authorizationStatus(id))
        assertFalse(server.isPaired(id))
    }

    @Test
    fun `a wrong code does not pair, and five wrong codes deny the sign-in`() {
        val client = server.register("Claude", listOf(redirect))
        val id = beginFor(client.id)
        val right =
            server.pending.value
                .first { it.id == id }
                .pairingCode
        val wrong = if (right == "AAAAAA") "BBBBBB" else "AAAAAA"
        repeat(4) { assertFalse(server.pair(id, wrong)) }
        assertFalse(server.isPaired(id))
        assertFalse(server.pair(id, wrong))
        assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id))
    }

    @Test
    fun `the code is typed in any case and pairing is checked once`() {
        val client = server.register("Claude", listOf(redirect))
        val id = beginFor(client.id)
        val code =
            server.pending.value
                .first { it.id == id }
                .pairingCode
        assertTrue(server.pair(id, code.lowercase()))
        assertTrue(server.isPaired(id))
        assertTrue(
            server.pending.value
                .first { it.id == id }
                .paired,
        )
    }

    @Test
    fun `grants survive a restart and the kill switch ends them`() {
        val client = server.register("Claude", listOf(redirect))
        val tokens = exchange(client.id, authorize(client.id))
        val restarted = AuthorizationServer(store, Clock { now })
        assertNotNull(restarted.authenticate(tokens.accessToken))
        restarted.revokeAll()
        assertNull(restarted.authenticate(tokens.accessToken))
        assertTrue(restarted.clients.value.isEmpty())
    }

    @Test
    fun `shell scopes are never granted`() {
        val client = server.register("Claude", listOf(redirect))
        val tokens = exchange(client.id, authorize(client.id, setOf(Scope.SHELL_EXEC, Scope.SCREEN_READ)))
        assertEquals(setOf(Scope.SCREEN_READ), assertNotNull(server.authenticate(tokens.accessToken)).scopes)
    }
}
