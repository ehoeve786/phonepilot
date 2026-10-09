package app.pocketpilot.server.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A registered client (RFC 7591). PocketPilot only has public clients, so there is no secret. */
@Serializable
data class OAuthClient(
    val id: String,
    val name: String,
    val redirectUris: List<String>,
    val createdAtMillis: Long,
)

/** What the owner approved for one client: its scopes, until revoked. */
@Serializable
data class Grant(
    val id: String,
    val clientId: String,
    val scopes: Set<String>,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long = createdAtMillis,
)

@Serializable
enum class TokenKind {
    ACCESS,
    REFRESH,
}

/** An issued token, kept only as its SHA-256 hash. */
@Serializable
data class TokenRecord(
    val hash: String,
    val grantId: String,
    val kind: TokenKind,
    val expiresAtMillis: Long,
    /** Refresh tokens are single use; a second use revokes the whole grant. */
    val used: Boolean = false,
)

@Serializable
data class OAuthState(
    val clients: List<OAuthClient> = emptyList(),
    val grants: List<Grant> = emptyList(),
    val tokens: List<TokenRecord> = emptyList(),
)

/** Where the authorization server keeps clients, grants and token hashes between restarts. */
interface OAuthStore {
    fun load(): OAuthState

    fun save(state: OAuthState)
}

class InMemoryOAuthStore(
    private var state: OAuthState = OAuthState(),
) : OAuthStore {
    override fun load(): OAuthState = state

    override fun save(state: OAuthState) {
        this.state = state
    }
}

/**
 * A JSON file in app-private storage, replaced atomically on each save. The spec's Room database
 * arrives with the other stored data in M8; this keeps the same records.
 */
class FileOAuthStore(
    private val file: File,
) : OAuthStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): OAuthState =
        runCatching { json.decodeFromString(OAuthState.serializer(), file.readText()) }
            .getOrDefault(OAuthState())

    override fun save(state: OAuthState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(OAuthState.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
