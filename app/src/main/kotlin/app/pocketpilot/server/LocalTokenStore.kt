package app.pocketpilot.server

import app.pocketpilot.network.api.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The per-install local token for on-device MCP hosts (spec section 8). Kept in the Keystore-backed
 * [SecretStore]. A token saved by an older version as a plain file in [legacyDirectory] is moved over on
 * first start.
 */
class LocalTokenStore(
    private val secrets: SecretStore,
    private val legacyDirectory: File,
    private val random: SecureRandom = SecureRandom(),
) {
    private val state = MutableStateFlow(load() ?: generateAndSave())

    val token: StateFlow<String> = state.asStateFlow()

    fun current(): String = state.value

    /** Replaces the token; clients using the old one get 401 on their next request. */
    @Synchronized
    fun rotate() {
        state.value = generateAndSave()
    }

    private fun load(): String? = (secrets.get(SECRET_NAME) ?: migrateLegacy())?.takeIf { it.length >= MIN_LENGTH }

    private fun migrateLegacy(): String? {
        val file = File(legacyDirectory, LEGACY_FILE_NAME)
        val token = file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= MIN_LENGTH } ?: return null
        secrets.put(SECRET_NAME, token)
        file.delete()
        return token
    }

    private fun generateAndSave(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = "pp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        secrets.put(SECRET_NAME, token)
        return token
    }

    private companion object {
        const val SECRET_NAME = "local_token"
        const val LEGACY_FILE_NAME = "local_token"
        const val TOKEN_BYTES = 32
        const val MIN_LENGTH = 32
    }
}
