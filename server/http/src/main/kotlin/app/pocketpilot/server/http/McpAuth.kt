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
            // MCP clients and OAuth redirects send no Origin; a web page in the phone's browser does.
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && originHost(origin) != host) {
                call.respondText("Cross-site requests are not allowed", status = HttpStatusCode.Forbidden)
                return@onCall
            }
            if (!call.request.path().startsWith(PROTECTED_RESOURCE_PATH)) return@onCall

            val address = call.clientAddress(config.remotePort)
            val presented =
                call.request
                    .header(HttpHeaders.Authorization)
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(BEARER_PREFIX_LENGTH)
                    ?.trim()

            // Checked before the lockout, so failures counted against an address never block the local token.
            if (presented != null && !remote && constantTimeEquals(presented, config.localToken())) {
                call.attributes.put(SessionStarter) { config.sessions.localTokenSession() }
                return@onCall
            }
            if (config.lockout.isBlocked(address)) {
                call.respondText("Too many failed attempts; try again in an hour", status = HttpStatusCode.TooManyRequests)
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

/**
 * The key failed attempts and rate limits are counted under. Remote clients arrive through a forwarder, so
 * their connection address is always the forwarder's, and X-Forwarded-For is written by the client, so it
 * cannot tell clients apart. All remote clients therefore share one key: a remote attacker can block remote
 * clients for an hour, but cannot block the local token or the apps on the phone.
 */
internal fun ApplicationCall.clientAddress(remotePort: Int): String =
    if (isRemote(remotePort)) REMOTE_KEY else request.local.remoteAddress

/** The host of an Origin header, lowercased; null for "null" or anything unparsable. */
internal fun originHost(origin: String): String? =
    runCatching {
        java.net
            .URI(origin)
            .host
            ?.lowercase()
    }.getOrNull()

private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")
private const val REMOTE_KEY = "remote"
private const val BEARER_PREFIX_LENGTH = 7

private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
