package app.pocketpilot.network.wireguard

import android.content.Context
import app.pocketpilot.network.api.NetworkProvider
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import app.pocketpilot.network.gonet.gen.ppnet.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the owner set up for WireGuard, without the private key. */
data class WireGuardSetup(
    val configured: Boolean = false,
    /** The phone's public key, for the server's peer list. */
    val publicKey: String? = null,
    /** The server's host from the config's Endpoint, which is also the relay. */
    val serverHost: String? = null,
    /** The newest handshake with the server, in Unix milliseconds, or 0. */
    val lastHandshake: Long = 0,
)

/**
 * WireGuard in userspace on its own network stack in the app (spec section 7): no VpnService, so
 * the rest of the phone's traffic is untouched. The owner imports a wg-quick config, typically
 * exported by their router's WireGuard server. The phone listens for HTTPS on port 443 of its tunnel
 * address with the [certificates] certificate, and a relay (the router or a server) forwards the
 * domain's port 443 to it.
 */
class WireGuardProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
    private val certificates: CertificateManager,
    private val targetPort: Int,
) : NetworkProvider {
    override val id: String = "wireguard"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var createdTunnel: Tunnel? = null

    private val mutableState = MutableStateFlow<NetworkState>(NetworkState.Stopped)
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    private val mutableSetup = MutableStateFlow(setupFrom(secrets.get(SECRET_CONFIG)))
    val setup: StateFlow<WireGuardSetup> = mutableSetup.asStateFlow()

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    private var poller: Job? = null

    init {
        // Deriving the public key loads the Go library, so it stays off the thread that builds this.
        scope.launch(Dispatchers.IO) {
            val config = secrets.get(SECRET_CONFIG) ?: return@launch
            mutableSetup.value = mutableSetup.value.copy(publicKey = publicKeyOf(config).getOrNull())
        }
    }

    /**
     * Checks and saves a wg-quick config. Returns a problem in plain words, or null when it is
     * usable. A running tunnel restarts with it.
     */
    fun configure(config: String): String? {
        val publicKey = publicKeyOf(config)
        publicKey.exceptionOrNull()?.let { return it.message ?: it.toString() }
        secrets.put(SECRET_CONFIG, config)
        mutableSetup.value = setupFrom(config).copy(publicKey = publicKey.getOrNull())
        if (poller?.isActive == true) {
            pause()
            start()
        }
        return null
    }

    fun resume() {
        if (enabled) start()
    }

    @Synchronized
    override fun start() {
        val config = secrets.get(SECRET_CONFIG) ?: return
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        if (poller?.isActive == true) return
        mutableState.value = NetworkState.Connecting
        poller =
            scope.launch(Dispatchers.IO) {
                try {
                    tunnel().start(config)
                } catch (e: Throwable) {
                    mutableState.value = NetworkState.Error(e.message ?: e.toString(), e.stackTraceToString())
                    return@launch
                }
                mutableSetup.value.serverHost?.let { certificates.followRelay(it) }
                while (isActive) {
                    refresh()
                    delay(POLL_MS)
                }
            }
    }

    @Synchronized
    override fun stop() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        pause()
    }

    /** Stops the tunnel when the foreground service ends, but keeps it on for the next [resume]. */
    @Synchronized
    fun pause() {
        poller?.cancel()
        poller = null
        scope.launch(Dispatchers.IO) { runCatching { createdTunnel?.stop() } }
        mutableState.value = NetworkState.Stopped
    }

    /** Forgets the config and its private key. */
    override fun logout() {
        stop()
        secrets.remove(SECRET_CONFIG)
        mutableSetup.value = WireGuardSetup()
    }

    fun logs(): String = runCatching { createdTunnel?.logs() }.getOrNull().orEmpty()

    @Synchronized
    private fun tunnel(): Tunnel = createdTunnel ?: Ppnet.newTunnel(targetPort.toLong(), certificates.native).also { createdTunnel = it }

    private fun refresh() {
        val status = runCatching { JSONObject(createdTunnel?.status() ?: return) }.getOrNull() ?: return
        val address = status.optJSONArray("addresses")?.optString(0).orEmpty()
        val handshake = status.optLong("lastHandshake")
        val error = status.optString("error").takeIf(String::isNotEmpty)
        mutableSetup.value = mutableSetup.value.copy(lastHandshake = handshake)
        val domain = certificates.domain
        mutableState.value =
            when {
                error != null -> NetworkState.Error(error)
                !status.optBoolean("running") -> NetworkState.Stopped
                handshake == 0L -> NetworkState.Connecting
                domain == null -> NetworkState.Error("Set your domain under Certificate so clients can reach the phone")
                else -> NetworkState.Connected(address, domain)
            }
    }

    private fun setupFrom(config: String?): WireGuardSetup {
        if (config == null) return WireGuardSetup()
        val endpoint =
            config
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
                ?.substringAfter('=')
                ?.trim()
        return WireGuardSetup(configured = true, serverHost = endpoint?.let(::hostOf))
    }

    private fun publicKeyOf(config: String): Result<String> =
        runCatching {
            // Sets up the Go library's Android context before the first call into it.
            certificates.native
            Ppnet.publicKeyOfConfig(config)
        }

    private companion object {
        const val PREFS = "network_wireguard"
        const val KEY_ENABLED = "enabled"
        const val SECRET_CONFIG = "wireguard_config"
        const val POLL_MS = 3_000L

        /** `host:port` or `[v6]:port` to the host. */
        fun hostOf(endpoint: String): String =
            if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
    }
}
