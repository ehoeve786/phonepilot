package app.pocketpilot.network.api

import kotlinx.coroutines.flow.StateFlow

/** Where a network provider stands (spec section 7). */
sealed interface NetworkState {
    /** Off; the owner has not turned it on. */
    data object Stopped : NetworkState

    data object Connecting : NetworkState

    /** Waiting for the owner to sign in at [url]. */
    data class NeedsAuth(
        val url: String,
    ) : NetworkState

    /**
     * On the private network at [address], reachable at https://[hostname]. For WireGuard the host
     * name is the owner's domain, which the relay forwards to [address].
     */
    data class Connected(
        val address: String,
        val hostname: String,
    ) : NetworkState

    data class Error(
        val reason: String,
        /** A crash report or stack trace the owner can copy and send for support. */
        val details: String? = null,
    ) : NetworkState
}

/**
 * A private network that carries remote MCP clients to the phone. It runs in the app process and
 * forwards accepted HTTPS connections to the loopback port the MCP server opens for remote traffic.
 */
interface NetworkProvider {
    /** `tailscale` or `wireguard`. */
    val id: String
    val state: StateFlow<NetworkState>

    /** Idempotent. */
    fun start()

    /** Idempotent; keeps the provider's login. */
    fun stop()

    /** Signs out and forgets the login. */
    fun logout()
}

/** Whether the public internet can reach the phone, and what still blocks it. */
data class PublicAccessState(
    val enabled: Boolean,
    /** The public MCP URL while enabled and working. */
    val url: String? = null,
    /** The setting the owner still has to change, in plain words, when public access cannot work. */
    val problem: String? = null,
)

/**
 * Opt-in public access on top of a provider (spec section 7). It is off by default, and every request
 * through it must carry an OAuth access token.
 */
interface PublicAccess {
    val publicState: StateFlow<PublicAccessState>

    fun setPublic(enabled: Boolean)
}
