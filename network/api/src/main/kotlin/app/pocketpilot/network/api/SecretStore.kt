package app.pocketpilot.network.api

/**
 * Small secrets such as WireGuard private keys and DNS API tokens, encrypted at rest with a key that
 * never leaves the Android Keystore (spec section 9). Values are never backed up.
 */
interface SecretStore {
    fun get(name: String): String?

    fun put(
        name: String,
        value: String,
    )

    fun remove(name: String)
}
