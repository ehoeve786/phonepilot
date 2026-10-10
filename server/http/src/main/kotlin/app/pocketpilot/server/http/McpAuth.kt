package app.pocketpilot.server.http

import app.pocketpilot.core.model.Session
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.server.oauth.Lockout
import app.pocketpilot.server.oauth.PROTECTED_RESOURCE_PATH
import app.pocketpilot.server.oauth.protectedResourceMetadataUrl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.security.MessageDigest

/** Who a request to the MCP endpoint acts as, once [McpAuth] has accepted it. */
internal val SessionStarter = AttributeKey<() -> Session>("pocketpilot.session")

class McpAuthConfig {
    /** The per-install local token, read on every request so rotation takes effect at once. */
    var localToken: () -> String = { error("McpAuth needs a local token provider") }

    /** OAuth for remote clients; without it only the local token works. */
    var oauth: AuthorizationServer? = null
    var lockout: Lockout = Lockout()
    var sessions: SessionFactory = SessionFactory()

    /** Host names the remote port answers to, such as the Tailscale node's `*.ts.net` name. */
    var remoteHosts: () -> Set<String> = { emptySet() }
    var remotePort: Int = -1
}

/**
 * Guards both loopback ports (spec section 5).
 *
 * - The local port, for apps on the phone, answers to localhost names only and accepts the local token
 *   or an OAuth access token.
 * - The remote port, fed only by a network provider's forwarder, answers to the provider's host names
 *   only and accepts OAuth access tokens only, so the local token never works from outside.
 *
 * A missing or bad token on `/mcp` gets 401 with the protected resource metadata URL, which is how
 * clients such as Claude find the authorization server.
 */
val McpAuth =
    createApplicationPlugin("McpAuth", ::McpAuthConfig) {
        val config = pluginConfig
        onCall { call ->
            val remote = call.isRemote(config.remotePort)
            val host = call.request.host().lowercase()
            val hostAllowed = if (remote) host in config.remoteHosts().map(String::lowercase) else host in LOCAL_HOSTS
            if (!hostAllowed) {
                call.respondText("Unknown host", status = HttpStatusCode.Forbidden)
                return@onCall
            }
            if (!call.request.path().startsWith(PROTECTED_RESOURCE_PATH)) return@onCall

            val address = call.clientAddress(config.remotePort)
            if (config.lockout.isBlocked(address)) {
                call.respondText("Too many failed attempts; try again in an hour", status = HttpStatusCode.TooManyRequests)
                return@onCall
            }
            val presented =
                call.request
                    .header(HttpHeaders.Authorization)
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(BEARER_PREFIX_LENGTH)
                    ?.trim()

            if (presented != null && !remote && constantTimeEquals(presented, config.localToken())) {
                call.attributes.put(SessionStarter) { config.sessions.localTokenSession() }
                return@onCall
            }
            val grant = presented?.let { config.oauth?.authenticate(it) }
            if (grant != null) {
                call.attributes.put(SessionStarter) { config.sessions.clientSession(grant.clientName, grant.scopes) }
                return@onCall
            }

            if (presented != null) config.lockout.recordFailure(address)
            val metadata = protectedResourceMetadataUrl(call.issuer(config.remotePort))
            call.response.header(
                HttpHeaders.WWWAuthenticate,
                "Bearer realm=\"pocketpilot\", resource_metadata=\"$metadata\"" +
                    if (presented != null) ", error=\"invalid_token\"" else "",
            )
            call.respondText(
                if (presented == null) "Sign in required" else "Invalid or expired token",
                status = HttpStatusCode.Unauthorized,
            )
        }
    }

internal fun ApplicationCall.isRemote(remotePort: Int): Boolean = request.local.localPort == remotePort

/** `https://<host>` on the remote port, `http://<host:port>` on the local one. */
internal fun ApplicationCall.issuer(remotePort: Int): String =
    if (isRemote(remotePort)) {
        "https://" + request.host()
    } else {
        "http://" + request.host() + ":" + request.local.localPort
    }

/** The caller's address; on the remote port that is the first X-Forwarded-For hop from the forwarder. */
internal fun ApplicationCall.clientAddress(remotePort: Int): String =
    if (isRemote(remotePort)) {
        request.header("X-Forwarded-For")?.substringBefore(',')?.trim() ?: request.local.remoteAddress
    } else {
        request.local.remoteAddress
    }

private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")
private const val BEARER_PREFIX_LENGTH = 7

private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
