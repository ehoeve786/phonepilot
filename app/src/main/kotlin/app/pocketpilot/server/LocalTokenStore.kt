package app.pocketpilot.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The per-install local token for on-device MCP hosts (spec section 8). Stored in app-private,
 * no-backup storage; moves to the Keystore-backed SecretStore when core/security lands (M5).
 */
class LocalTokenStore(
    directory: File,
    private val random: SecureRandom = SecureRandom(),
) {
    private val file = File(directory, FILE_NAME)
    private val state = MutableStateFlow(load() ?: generateAndSave())

    val token: StateFlow<String> = state.asStateFlow()

    fun current(): String = state.value

    /** Replaces the token; clients using the old one get 401 on their next request. */
    @Synchronized
    fun rotate() {
        state.value = generateAndSave()
    }

    private fun load(): String? =
        file
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.length >= MIN_LENGTH }

    private fun generateAndSave(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = "pp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        file.writeText(token)
        return token
    }

    private companion object {
        const val FILE_NAME = "local_token"
        const val TOKEN_BYTES = 32
        const val MIN_LENGTH = 32
    }
}
