package app.pocketpilot.automation

import app.pocketpilot.network.api.SecretStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The key an "Event received" broadcast must carry in its `key` extra. Any app can send the broadcast,
 * so the key is what tells the owner's own senders (Tasker, MacroDroid) apart from the rest. The owner
 * copies it from Settings into the sender.
 */
object EventKey {
    private const val SECRET_NAME = "event_key"
    private const val BYTES = 32
    private val random = SecureRandom()

    @Synchronized
    fun get(secrets: SecretStore): String = secrets.get(SECRET_NAME) ?: generate().also { secrets.put(SECRET_NAME, it) }

    private fun generate(): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        return "ppe_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** True when [given] is the key [expected], compared in constant time. */
    fun matches(
        expected: String,
        given: String?,
    ): Boolean = given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
}
