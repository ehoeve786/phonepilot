package app.pocketpilot.server.oauth

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Scope
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        server.approve(id, scopes)
        val status = assertIs<AuthorizationStatus.Redirect>(server.authorizationStatus(id))
        return status.url.substringAfter("code=").substringBefore('&')
    }

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
        assertEquals("Claude", grant.clientName)
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

    @Test
    fun `only https or loopback redirects can register`() {
        assertFailsWith<OAuthException> { server.register("x", listOf("http://example.com/cb")) }
        assertFailsWith<OAuthException> { server.register("x", listOf("myapp://cb")) }
        server.register("x", listOf("http://127.0.0.1:3000/cb"))
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
