package app.pocketpilot.server.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.security.MessageDigest

class LocalTokenAuthConfig {
    /** Returns the current local token; read on every request so rotation takes effect at once. */
    var token: () -> String = { error("LocalTokenAuth needs a token provider") }

    /** Paths below this prefix require the token. */
    var protectedPathPrefix: String = "/mcp"
}

/**
 * Requires `Authorization: Bearer <local token>` on the MCP endpoint. This is the M2 stand-in for the
 * OAuth 2.1 server in spec section 8, used by hosts on the phone itself, such as Claude Code in Termux.
 */
val LocalTokenAuth =
    createApplicationPlugin("LocalTokenAuth", ::LocalTokenAuthConfig) {
        val tokenProvider = pluginConfig.token
        val prefix = pluginConfig.protectedPathPrefix
        onCall { call ->
            if (!call.request.path().startsWith(prefix)) return@onCall
            val presented =
                call.request
                    .header(HttpHeaders.Authorization)
                    ?.removePrefix("Bearer ")
                    ?.trim()
            if (presented == null || !constantTimeEquals(presented, tokenProvider())) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pocketpilot\"")
                call.respondText("Missing or invalid local token", status = HttpStatusCode.Unauthorized)
            }
        }
    }

private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
