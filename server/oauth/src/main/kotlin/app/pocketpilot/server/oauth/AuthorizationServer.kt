package app.pocketpilot.server.oauth

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** An OAuth error, sent as `{"error": code, "error_description": description}`. */
class OAuthException(
    val code: String,
    val description: String,
    val status: Int = 400,
) : Exception("$code: $description")

/** A request waiting for the owner's answer on the phone. */
data class ConsentRequest(
    val id: String,
    val clientId: String,
    val clientName: String,
    /** The host the browser returns to, which tells the owner who is asking. */
    val redirectHost: String,
    /** Scopes the client asked for; empty means it left the choice to the owner. */
    val requestedScopes: Set<Scope>,
    val createdAtMillis: Long,
)

/** Where a browser waiting on `/authorize` stands. */
sealed interface AuthorizationStatus {
    data object Waiting : AuthorizationStatus

    /** Send the browser here: the client's redirect URI with a code, or with an error. */
    data class Redirect(
        val url: String,
    ) : AuthorizationStatus

    data object Unknown : AuthorizationStatus
}

/** A valid access token: who it belongs to and what it may do. */
data class AccessGrant(
    val clientId: String,
    val clientName: String,
    val grantId: String,
    val scopes: Set<Scope>,
)

/** One client as the Clients screen shows it. */
data class ClientSummary(
    val id: String,
    val name: String,
    val redirectHost: String,
    val scopes: Set<Scope>,
    val connected: Boolean,
    val lastUsedAtMillis: Long?,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val scope: String,
)

/**
 * The phone's own OAuth 2.1 authorization server (spec section 8): dynamic client registration,
 * authorization code with PKCE (S256 only), rotating single-use refresh tokens with reuse detection,
 * and revocation per client or all at once. Approval happens on the phone: [beginAuthorization] adds a
 * [ConsentRequest] to [pending], and the app answers it with [approve] or [deny].
 */
class AuthorizationServer(
    private val store: OAuthStore,
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) {
    private var state: OAuthState = store.load()
    private val codes = HashMap<String, AuthorizationCode>()
    private val waiting = HashMap<String, PendingAuthorization>()

    private val mutablePending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    val pending: StateFlow<List<ConsentRequest>> = mutablePending.asStateFlow()

    private val mutableClients = MutableStateFlow(summaries())
    val clients: StateFlow<List<ClientSummary>> = mutableClients.asStateFlow()

    private class AuthorizationCode(
        val clientId: String,
        val redirectUri: String,
        val codeChallenge: String,
        val grantId: String,
        val expiresAtMillis: Long,
    )

    private class PendingAuthorization(
        val request: ConsentRequest,
        val redirectUri: String,
        val state: String?,
        val codeChallenge: String,
        var result: AuthorizationStatus = AuthorizationStatus.Waiting,
    )

    // Registration (RFC 7591)

    /** Registers a public client. [redirectUris] must be https, or http on a loopback address. */
    @Synchronized
    fun register(
        name: String?,
        redirectUris: List<String>,
    ): OAuthClient {
        if (redirectUris.isEmpty()) throw OAuthException("invalid_redirect_uri", "redirect_uris is required")
        redirectUris.forEach { uri ->
            if (!isAllowedRedirect(uri)) {
                throw OAuthException("invalid_redirect_uri", "Redirect URIs must be https, or http on localhost: $uri")
            }
        }
        if (state.clients.size >= MAX_CLIENTS) {
            dropOldestUnusedClient() ?: throw OAuthException("invalid_client_metadata", "Too many clients are registered")
        }
        val client =
            OAuthClient(
                id = "ppc_" + randomToken(CLIENT_ID_BYTES),
                name = name?.trim()?.take(MAX_NAME_LENGTH)?.takeIf(String::isNotEmpty) ?: hostOf(redirectUris.first()),
                redirectUris = redirectUris,
                createdAtMillis = clock.nowMillis(),
            )
        update { it.copy(clients = it.clients + client) }
        return client
    }

    // Authorization

    /**
     * Validates an authorization request and puts it in front of the owner. Returns the id the
     * browser waits on. Errors about the client or redirect URI are thrown, since they must not
     * redirect; other errors redirect back to the client.
     */
    @Synchronized
    fun beginAuthorization(params: Map<String, String>): String {
        val client =
            state.clients.firstOrNull { it.id == params["client_id"] }
                ?: throw OAuthException("invalid_client", "Unknown client_id. Register the client first.")
        val redirectUri =
            params["redirect_uri"]
                ?: client.redirectUris.singleOrNull()
                ?: throw OAuthException("invalid_request", "redirect_uri is required")
        if (redirectUri !in client.redirectUris) {
            throw OAuthException("invalid_request", "redirect_uri does not match a registered URI")
        }

        val id = "par_" + randomToken(REQUEST_ID_BYTES)
        val oauthState = params["state"]

        fun fail(
            code: String,
            description: String,
        ): String {
            waiting[id] =
                PendingAuthorization(
                    request = ConsentRequest(id, client.id, client.name, hostOf(redirectUri), emptySet(), clock.nowMillis()),
                    redirectUri = redirectUri,
                    state = oauthState,
                    codeChallenge = "",
                    result = AuthorizationStatus.Redirect(errorRedirect(redirectUri, code, description, oauthState)),
                )
            return id
        }

        if (params["response_type"] != "code") return fail("unsupported_response_type", "Only response_type=code is supported")
        val challenge = params["code_challenge"]
        if (challenge.isNullOrEmpty()) return fail("invalid_request", "PKCE is required: send code_challenge")
        if (params["code_challenge_method"] != "S256") return fail("invalid_request", "Only code_challenge_method=S256 is supported")

        val requested = parseScopes(params["scope"]).filter { it in GRANTABLE }.toSet()
        val request =
            ConsentRequest(
                id = id,
                clientId = client.id,
                clientName = client.name,
                redirectHost = hostOf(redirectUri),
                requestedScopes = requested,
                createdAtMillis = clock.nowMillis(),
            )
        expireStale()
        waiting[id] = PendingAuthorization(request, redirectUri, oauthState, challenge)
        mutablePending.update { it + request }
        return id
    }

    /** The owner approved [requestId] with [scopes]. */
    @Synchronized
    fun approve(
        requestId: String,
        scopes: Set<Scope>,
    ) {
        val pendingAuth = waiting[requestId] ?: return
        if (pendingAuth.result != AuthorizationStatus.Waiting) return
        val now = clock.nowMillis()
        val grant =
            Grant(
                id = "ppg_" + randomToken(REQUEST_ID_BYTES),
                clientId = pendingAuth.request.clientId,
                scopes = scopes.filter { it in GRANTABLE }.map(Scope::value).toSet(),
                createdAtMillis = now,
            )
        update { it.copy(grants = it.grants + grant) }
        val code = "ppac_" + randomToken(TOKEN_BYTES)
        codes[code] =
            AuthorizationCode(
                clientId = pendingAuth.request.clientId,
                redirectUri = pendingAuth.redirectUri,
                codeChallenge = pendingAuth.codeChallenge,
                grantId = grant.id,
                expiresAtMillis = now + CODE_LIFETIME_MS,
            )
        pendingAuth.result = AuthorizationStatus.Redirect(withQuery(pendingAuth.redirectUri, "code" to code, "state" to pendingAuth.state))
        mutablePending.update { list -> list.filterNot { it.id == requestId } }
    }

    @Synchronized
    fun deny(requestId: String) {
        val pendingAuth = waiting[requestId] ?: return
        if (pendingAuth.result != AuthorizationStatus.Waiting) return
        pendingAuth.result =
            AuthorizationStatus.Redirect(
                errorRedirect(pendingAuth.redirectUri, "access_denied", "The phone's owner declined", pendingAuth.state),
            )
        mutablePending.update { list -> list.filterNot { it.id == requestId } }
    }

    @Synchronized
    fun authorizationStatus(requestId: String): AuthorizationStatus {
        expireStale()
        return waiting[requestId]?.result ?: AuthorizationStatus.Unknown
    }

    // Tokens

    /** The token endpoint, for `authorization_code` and `refresh_token` grants. */
    @Synchronized
    fun token(params: Map<String, String>): TokenResponse =
        when (params["grant_type"]) {
            "authorization_code" -> exchangeCode(params)
            "refresh_token" -> refresh(params)
            else -> throw OAuthException("unsupported_grant_type", "Use authorization_code or refresh_token")
        }

    private fun exchangeCode(params: Map<String, String>): TokenResponse {
        val code = codes.remove(params["code"].orEmpty()) ?: throw OAuthException("invalid_grant", "Unknown or used code")
        if (code.expiresAtMillis < clock.nowMillis()) throw OAuthException("invalid_grant", "The code expired")
        if (params["client_id"] != null && params["client_id"] != code.clientId) {
            throw OAuthException("invalid_grant", "The code was issued to another client")
        }
        if (params["redirect_uri"] != null && params["redirect_uri"] != code.redirectUri) {
            throw OAuthException("invalid_grant", "redirect_uri does not match the authorization request")
        }
        val verifier = params["code_verifier"] ?: throw OAuthException("invalid_request", "code_verifier is required")
        if (!pkceMatches(verifier, code.codeChallenge)) throw OAuthException("invalid_grant", "code_verifier does not match")
        return issue(code.grantId)
    }

    private fun refresh(params: Map<String, String>): TokenResponse {
        val presented = params["refresh_token"] ?: throw OAuthException("invalid_request", "refresh_token is required")
        val hash = sha256(presented)
        val record =
            state.tokens.firstOrNull { it.hash == hash && it.kind == TokenKind.REFRESH }
                ?: throw OAuthException("invalid_grant", "Unknown refresh token")
        val grant = state.grants.firstOrNull { it.id == record.grantId } ?: throw OAuthException("invalid_grant", "The grant was revoked")
        if (params["client_id"] != null && params["client_id"] != grant.clientId) {
            throw OAuthException("invalid_grant", "The token was issued to another client")
        }
        if (record.used) {
            // Reuse means the token leaked: end the whole grant (spec section 8).
            revokeGrant(grant.id)
            throw OAuthException("invalid_grant", "Refresh token reuse; the grant is revoked")
        }
        if (record.expiresAtMillis < clock.nowMillis()) throw OAuthException("invalid_grant", "The refresh token expired")
        update { s -> s.copy(tokens = s.tokens.map { if (it.hash == hash) it.copy(used = true) else it }) }
        return issue(grant.id)
    }

    private fun issue(grantId: String): TokenResponse {
        val grant = state.grants.first { it.id == grantId }
        val now = clock.nowMillis()
        val access = "ppat_" + randomToken(TOKEN_BYTES)
        val refresh = "pprt_" + randomToken(TOKEN_BYTES)
        update { s ->
            s.copy(
                tokens =
                    s.tokens.filter { it.expiresAtMillis > now } +
                        TokenRecord(sha256(access), grantId, TokenKind.ACCESS, now + ACCESS_LIFETIME_MS) +
                        TokenRecord(sha256(refresh), grantId, TokenKind.REFRESH, now + REFRESH_LIFETIME_MS),
                grants = s.grants.map { if (it.id == grantId) it.copy(lastUsedAtMillis = now) else it },
            )
        }
        return TokenResponse(access, refresh, ACCESS_LIFETIME_MS / MILLIS_PER_SECOND, grant.scopes.sorted().joinToString(" "))
    }

    /** Resolves a bearer access token, or null when it is unknown, expired or revoked. */
    @Synchronized
    fun authenticate(accessToken: String): AccessGrant? {
        val hash = sha256(accessToken)
        val record = state.tokens.firstOrNull { it.hash == hash && it.kind == TokenKind.ACCESS } ?: return null
        if (record.expiresAtMillis < clock.nowMillis()) return null
        val grant = state.grants.firstOrNull { it.id == record.grantId } ?: return null
        val client = state.clients.firstOrNull { it.id == grant.clientId } ?: return null
        return AccessGrant(client.id, client.name, grant.id, grant.scopes.map(::Scope).toSet())
    }

    // Revocation

    /** Revokes every grant of [clientId] and forgets the client, from the Clients screen. */
    @Synchronized
    fun revokeClient(clientId: String) {
        val grantIds =
            state.grants
                .filter { it.clientId == clientId }
                .map(Grant::id)
                .toSet()
        update { s ->
            s.copy(
                clients = s.clients.filterNot { it.id == clientId },
                grants = s.grants.filterNot { it.id in grantIds },
                tokens = s.tokens.filterNot { it.grantId in grantIds },
            )
        }
        codes.values.removeAll { it.clientId == clientId }
    }

    /** The kill switch: every client loses access at once. */
    @Synchronized
    fun revokeAll() {
        update { OAuthState() }
        codes.clear()
        waiting.keys.toList().forEach(::deny)
    }

    /** RFC 7009: revoking either token of a grant ends the grant. Unknown tokens are ignored. */
    @Synchronized
    fun revokeToken(token: String) {
        val hash = sha256(token)
        state.tokens.firstOrNull { it.hash == hash }?.let { revokeGrant(it.grantId) }
    }

    private fun revokeGrant(grantId: String) {
        update { s -> s.copy(grants = s.grants.filterNot { it.id == grantId }, tokens = s.tokens.filterNot { it.grantId == grantId }) }
    }

    // Internals

    private fun update(change: (OAuthState) -> OAuthState) {
        state = change(state)
        store.save(state)
        mutableClients.value = summaries()
    }

    private fun summaries(): List<ClientSummary> =
        state.clients.map { client ->
            val grants = state.grants.filter { it.clientId == client.id }
            ClientSummary(
                id = client.id,
                name = client.name,
                redirectHost = hostOf(client.redirectUris.first()),
                scopes = grants.flatMap { it.scopes }.map(::Scope).toSet(),
                connected = grants.isNotEmpty(),
                lastUsedAtMillis = grants.maxOfOrNull { it.lastUsedAtMillis },
            )
        }

    private fun dropOldestUnusedClient(): Unit? {
        val withGrants = state.grants.map(Grant::clientId).toSet()
        val victim = state.clients.filter { it.id !in withGrants }.minByOrNull(OAuthClient::createdAtMillis) ?: return null
        update { s -> s.copy(clients = s.clients.filterNot { it.id == victim.id }) }
        return Unit
    }

    private fun expireStale() {
        val cutoff = clock.nowMillis() - PENDING_LIFETIME_MS
        val stale = waiting.filterValues { it.request.createdAtMillis < cutoff }.keys
        if (stale.isEmpty()) return
        waiting.keys.removeAll(stale)
        mutablePending.update { list -> list.filterNot { it.id in stale } }
        codes.values.removeAll { it.expiresAtMillis < clock.nowMillis() }
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    companion object {
        /** Scopes a remote client can be granted; shell and Termux stay behind their own toggles. */
        val GRANTABLE: Set<Scope> = Scope.KNOWN - setOf(Scope.SHELL_EXEC, Scope.TERMUX_RUN)

        const val ACCESS_LIFETIME_MS = 60 * 60 * 1000L
        const val REFRESH_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000L
        const val CODE_LIFETIME_MS = 60 * 1000L

        /** How long a browser may wait for the owner to answer. */
        const val PENDING_LIFETIME_MS = 5 * 60 * 1000L

        private const val TOKEN_BYTES = 32
        private const val CLIENT_ID_BYTES = 16
        private const val REQUEST_ID_BYTES = 16
        private const val MAX_CLIENTS = 50
        private const val MAX_NAME_LENGTH = 80
        private const val MILLIS_PER_SECOND = 1000L
        private const val MIN_VERIFIER = 43
        private const val MAX_VERIFIER = 128

        fun parseScopes(value: String?): Set<Scope> =
            value
                .orEmpty()
                .split(' ')
                .filter(String::isNotBlank)
                .mapNotNull { runCatching { Scope(it) }.getOrNull() }
                .toSet()

        fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun pkceMatches(
            verifier: String,
            challenge: String,
        ): Boolean {
            if (verifier.length !in MIN_VERIFIER..MAX_VERIFIER) return false
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return MessageDigest.isEqual(computed.toByteArray(), challenge.toByteArray())
        }

        fun isAllowedRedirect(uri: String): Boolean {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
            if (parsed.fragment != null || parsed.host.isNullOrEmpty()) return false
            return when (parsed.scheme) {
                "https" -> true
                "http" -> parsed.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
                else -> false
            }
        }

        fun hostOf(uri: String): String = runCatching { URI(uri).host }.getOrNull() ?: uri

        private fun withQuery(
            uri: String,
            vararg params: Pair<String, String?>,
        ): String {
            val query =
                params
                    .filter { it.second != null }
                    .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
            return uri + (if ('?' in uri) "&" else "?") + query
        }

        private fun errorRedirect(
            uri: String,
            code: String,
            description: String,
            state: String?,
        ) = withQuery(uri, "error" to code, "error_description" to description, "state" to state)
    }
}
