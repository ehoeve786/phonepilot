package app.pocketpilot.server.oauth

import app.pocketpilot.core.model.Scope
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Path of the MCP endpoint these routes protect. */
const val PROTECTED_RESOURCE_PATH = "/mcp"

/** The protected resource metadata URL that a 401 from the MCP endpoint points clients to. */
fun protectedResourceMetadataUrl(issuer: String) = "$issuer/.well-known/oauth-protected-resource"

/**
 * The OAuth endpoints (spec section 8): metadata (RFC 8414 and RFC 9728), registration (RFC 7591),
 * authorize, token and revocation (RFC 7009).
 *
 * [issuer] gives the public base URL a request arrived on, such as `https://pocketpilot-x.ts.net`, and
 * [clientAddress] the address used for lockout.
 */
fun Route.oauthRoutes(
    server: AuthorizationServer,
    lockout: Lockout,
    issuer: (ApplicationCall) -> String,
    clientAddress: (ApplicationCall) -> String,
) {
    val metadata: (ApplicationCall) -> JsonObject = { call -> authorizationServerMetadata(issuer(call)) }
    get("/.well-known/oauth-authorization-server") { call.respondJson(metadata(call)) }
    get("/.well-known/oauth-authorization-server/mcp") { call.respondJson(metadata(call)) }
    get("/.well-known/openid-configuration") { call.respondJson(metadata(call)) }
    get("/.well-known/oauth-protected-resource") { call.respondJson(protectedResourceMetadata(issuer(call))) }
    get("/.well-known/oauth-protected-resource/mcp") { call.respondJson(protectedResourceMetadata(issuer(call))) }

    post("/register") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (body == null) {
            call.respondOAuthError(OAuthException("invalid_client_metadata", "The body must be a JSON object"))
            return@post
        }
        try {
            val redirectUris =
                body["redirect_uris"]?.let { element -> element.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()
            val client = server.register(body["client_name"]?.jsonPrimitive?.content, redirectUris)
            call.respondJson(
                buildJsonObject {
                    put("client_id", client.id)
                    put("client_id_issued_at", client.createdAtMillis / MILLIS_PER_SECOND)
                    put("client_name", client.name)
                    put("redirect_uris", JsonArray(client.redirectUris.map(::JsonPrimitive)))
                    put("token_endpoint_auth_method", "none")
                    putJsonArray("grant_types") {
                        add(JsonPrimitive("authorization_code"))
                        add(JsonPrimitive("refresh_token"))
                    }
                    putJsonArray("response_types") { add(JsonPrimitive("code")) }
                },
                HttpStatusCode.Created,
            )
        } catch (e: OAuthException) {
            call.respondOAuthError(e)
        } catch (e: IllegalArgumentException) {
            call.respondOAuthError(OAuthException("invalid_client_metadata", "redirect_uris must be an array of strings"))
        }
    }

    get("/authorize") {
        val params =
            call.request.queryParameters
                .entries()
                .associate { it.key to it.value.first() }
        try {
            val id = server.beginAuthorization(params)
            call.respondWaitingOrRedirect(server, id)
        } catch (e: OAuthException) {
            call.respondHtml(errorPage(e.description), HttpStatusCode.BadRequest)
        }
    }

    get("/authorize/wait") {
        call.respondWaitingOrRedirect(server, call.request.queryParameters["request"].orEmpty())
    }

    post("/token") {
        val address = clientAddress(call)
        if (lockout.isBlocked(address)) {
            call.respondOAuthError(OAuthException("invalid_client", "Too many failed attempts; try again in an hour", 429))
            return@post
        }
        val params = call.receiveParameters().entries().associate { it.key to it.value.first() }
        try {
            val tokens = server.token(params)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondJson(
                buildJsonObject {
                    put("access_token", tokens.accessToken)
                    put("token_type", "Bearer")
                    put("expires_in", tokens.expiresInSeconds)
                    put("refresh_token", tokens.refreshToken)
                    put("scope", tokens.scope)
                },
            )
        } catch (e: OAuthException) {
            if (e.code == "invalid_grant") lockout.recordFailure(address)
            call.respondOAuthError(e)
        }
    }

    post("/revoke") {
        call.receiveParameters()["token"]?.let(server::revokeToken)
        call.respondText("", status = HttpStatusCode.OK)
    }
}

/** RFC 8414 metadata for [issuer]. */
fun authorizationServerMetadata(issuer: String): JsonObject =
    buildJsonObject {
        put("issuer", issuer)
        put("authorization_endpoint", "$issuer/authorize")
        put("token_endpoint", "$issuer/token")
        put("registration_endpoint", "$issuer/register")
        put("revocation_endpoint", "$issuer/revoke")
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("grant_types_supported") {
            add(JsonPrimitive("authorization_code"))
            add(JsonPrimitive("refresh_token"))
        }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") { add(JsonPrimitive("none")) }
        put("scopes_supported", scopesJson())
    }

/** RFC 9728 metadata for the MCP endpoint behind [issuer]. */
fun protectedResourceMetadata(issuer: String): JsonObject =
    buildJsonObject {
        put("resource", issuer + PROTECTED_RESOURCE_PATH)
        putJsonArray("authorization_servers") { add(JsonPrimitive(issuer)) }
        putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
        put("scopes_supported", scopesJson())
        put("resource_name", "PocketPilot")
    }

private fun scopesJson() =
    JsonArray(
        AuthorizationServer.GRANTABLE
            .map(Scope::value)
            .sorted()
            .map(::JsonPrimitive),
    )

private suspend fun ApplicationCall.respondWaitingOrRedirect(
    server: AuthorizationServer,
    requestId: String,
) {
    when (val status = server.authorizationStatus(requestId)) {
        is AuthorizationStatus.Redirect -> {
            respondRedirect(status.url, permanent = false)
        }

        AuthorizationStatus.Waiting -> {
            response.header(HttpHeaders.CacheControl, "no-store")
            respondHtml(waitingPage(requestId))
        }

        AuthorizationStatus.Unknown -> {
            respondHtml(errorPage("This request expired. Go back to your app and connect again."), HttpStatusCode.NotFound)
        }
    }
}

private suspend fun ApplicationCall.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondOAuthError(e: OAuthException) =
    respondJson(
        buildJsonObject {
            put("error", e.code)
            put("error_description", e.description)
        },
        HttpStatusCode.fromValue(e.status),
    )

private suspend fun ApplicationCall.respondHtml(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)

private fun page(
    title: String,
    body: String,
    head: String = "",
) = """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>$title</title>$head
    <style>
    body{font-family:system-ui,sans-serif;max-width:28rem;margin:4rem auto;padding:0 1rem;line-height:1.5;color:#1b1b1f;background:#fbfbfe}
    h1{font-size:1.4rem}p{color:#46464f}
    @media (prefers-color-scheme:dark){body{color:#e4e1e6;background:#1b1b1f}p{color:#c7c5d0}}
    </style></head><body>$body</body></html>
    """.trimIndent()

private fun waitingPage(requestId: String) =
    page(
        title = "Approve on your phone",
        head = """<meta http-equiv="refresh" content="2;url=/authorize/wait?request=${requestId.htmlEscape()}">""",
        body =
            "<h1>Approve on your phone</h1>" +
                "<p>PocketPilot is asking the phone's owner to approve this connection. Open the notification on the phone, " +
                "check the app and permissions, and tap Allow.</p><p>This page continues by itself.</p>",
    )

private fun errorPage(message: String) =
    page(title = "Cannot connect", body = "<h1>Cannot connect to PocketPilot</h1><p>${message.htmlEscape()}</p>")

private fun String.htmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private const val MILLIS_PER_SECOND = 1000L
