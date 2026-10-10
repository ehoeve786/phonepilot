package app.pocketpilot.network.zerotier

import android.content.Context
import app.pocketpilot.network.api.NetworkProvider
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.gonet.gen.ppnet.Ppnet
import app.pocketpilot.network.gonet.gen.ppnet.TLSFrontend
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread

/** What the owner set up for ZeroTier. */
data class ZeroTierSetup(
    /** The 16-hex-digit network ID, or empty. */
    val networkId: String = "",
    /** This phone's 10-hex-digit node ID, known once the node has started. */
    val nodeId: String? = null,
)

/**
 * ZeroTier through libzt, a userspace node and network stack in the app process (spec section 7):
 * no VpnService. The phone joins the owner's network and waits until it is authorized in the
 * network's controller. It then listens on port 443 of its ZeroTier address and pipes each
 * connection to a loopback HTTPS frontend that presents the [certificates] certificate and forwards
 * to the remote MCP port. A relay (the router or a server on the same network) forwards the
 * domain's port 443 to the phone's ZeroTier address.
 *
 * libzt allows one node per process, and a stopped node cannot start again until the process
 * restarts, so turning ZeroTier off leaves the network and closes the listener but keeps the node.
 */
class ZeroTierProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val certificates: CertificateManager,
    private val targetPort: Int,
) : NetworkProvider {
    override val id: String = "zerotier"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val storage = File(context.noBackupFilesDir, "zerotier")

    private val mutableState = MutableStateFlow<NetworkState>(NetworkState.Stopped)
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    private val mutableSetup = MutableStateFlow(ZeroTierSetup(networkId = prefs.getString(KEY_NETWORK, "").orEmpty()))
    val setup: StateFlow<ZeroTierSetup> = mutableSetup.asStateFlow()

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    @Volatile private var node: ZeroTierNode? = null

    @Volatile private var lastNetworkEvent = 0

    @Volatile private var listener: ZeroTierSocket? = null

    private var frontend: TLSFrontend? = null
    private var joined: Long? = null
    private var poller: Job? = null

    /** Built on first use: touching ZeroTierNative loads libzt, which belongs off the main thread. */
    private val networkEvents by lazy {
        setOf(
            ZeroTierNative.ZTS_EVENT_NETWORK_NOT_FOUND,
            ZeroTierNative.ZTS_EVENT_NETWORK_REQ_CONFIG,
            ZeroTierNative.ZTS_EVENT_NETWORK_OK,
            ZeroTierNative.ZTS_EVENT_NETWORK_ACCESS_DENIED,
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4,
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP6,
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4_IP6,
            ZeroTierNative.ZTS_EVENT_NETWORK_DOWN,
        )
    }

    /** Saves the network to join. Returns a problem in plain words, or null when the ID looks right. */
    fun configure(networkId: String): String? {
        val id = networkId.trim().lowercase()
        if (!NETWORK_ID.matches(id)) return "A ZeroTier network ID is 16 characters, 0-9 and a-f"
        prefs.edit().putString(KEY_NETWORK, id).apply()
        mutableSetup.value = mutableSetup.value.copy(networkId = id)
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
        val networkId = mutableSetup.value.networkId.takeIf(String::isNotEmpty) ?: return
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        if (poller?.isActive == true) return
        mutableState.value = NetworkState.Connecting
        poller =
            scope.launch(Dispatchers.IO) {
                try {
                    connect(networkId.toULong(HEX).toLong())
                } catch (e: Throwable) {
                    // Errors too: a missing native library is an UnsatisfiedLinkError.
                    if (isActive) mutableState.value = NetworkState.Error(e.message ?: e.toString(), e.stackTraceToString())
                }
            }
    }

    @Synchronized
    override fun stop() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        pause()
    }

    /** Leaves the network when the foreground service ends, but keeps ZeroTier on for [resume]. */
    @Synchronized
    fun pause() {
        poller?.cancel()
        poller = null
        closeListener()
        joined?.let { id -> runCatching { node?.leave(id) } }
        joined = null
        mutableState.value = NetworkState.Stopped
    }

    /** Leaves the network and forgets it. The node's identity stays, so re-joining needs no new approval. */
    override fun logout() {
        stop()
        prefs.edit().remove(KEY_NETWORK).apply()
        mutableSetup.value = mutableSetup.value.copy(networkId = "")
    }

    fun logs(): String = runCatching { frontend?.logs() }.getOrNull().orEmpty()

    private suspend fun CoroutineScope.connect(networkId: Long) {
        val node = startNode()
        while (isActive && !node.isOnline) delay(POLL_MS)
        lastNetworkEvent = 0
        node.join(networkId)
        joined = networkId
        val approveUrl = "https://my.zerotier.com/network/%016x".format(networkId)
        var address: InetAddress? = null
        while (isActive) {
            val assigned = runCatching { node.getIPv4Address(networkId) }.getOrNull()
            when {
                lastNetworkEvent == ZeroTierNative.ZTS_EVENT_NETWORK_NOT_FOUND -> {
                    mutableState.value = NetworkState.Error("ZeroTier network ${"%016x".format(networkId)} does not exist")
                }

                assigned == null -> {
                    mutableState.value =
                        if (lastNetworkEvent == ZeroTierNative.ZTS_EVENT_NETWORK_ACCESS_DENIED) {
                            NetworkState.NeedsAuth(approveUrl, mutableSetup.value.nodeId)
                        } else {
                            NetworkState.Connecting
                        }
                }

                assigned != address -> {
                    address = assigned
                    closeListener()
                    listen(assigned)
                }
            }
            val current = address
            if (current != null && listener != null) {
                val domain = certificates.domain
                mutableState.value =
                    if (domain == null) {
                        NetworkState.Error("Set your domain under Certificate so clients can reach the phone")
                    } else {
                        NetworkState.Connected(current.hostAddress.orEmpty(), domain)
                    }
            }
            delay(POLL_MS)
        }
    }

    @Synchronized
    private fun startNode(): ZeroTierNode =
        node ?: ZeroTierNode().also { created ->
            storage.mkdirs()
            created.initFromStorage(storage.absolutePath)
            created.initSetEventHandler(
                ZeroTierEventListener { _, code ->
                    // Called on libzt's own thread, which does not catch exceptions: keep it trivial.
                    if (code in networkEvents) lastNetworkEvent = code
                },
            )
            val result = created.start()
            check(result == ZeroTierNative.ZTS_ERR_OK) { "ZeroTier did not start ($result)" }
            node = created
            mutableSetup.value = mutableSetup.value.copy(nodeId = "%010x".format(created.id))
        }

    /** Opens port 443 on the ZeroTier address and pipes each connection to the HTTPS frontend. */
    private fun listen(address: InetAddress) {
        val frontendPort = frontend().start().toInt()
        val socket = ZeroTierSocket(ZeroTierNative.ZTS_AF_INET, ZeroTierNative.ZTS_SOCK_STREAM, 0)
        socket.bind(address, HTTPS_PORT)
        socket.listen(BACKLOG)
        listener = socket
        thread(name = "zerotier-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "zerotier-conn", isDaemon = true) { pipe(client, frontendPort) }
            }
        }
    }

    private fun pipe(
        client: ZeroTierSocket,
        frontendPort: Int,
    ) {
        val local = runCatching { Socket("127.0.0.1", frontendPort) }.getOrNull()
        if (local == null) {
            runCatching { client.close() }
            return
        }
        val upstream =
            thread(name = "zerotier-up", isDaemon = true) {
                copy(client.inputStream, local.getOutputStream())
                runCatching { local.shutdownOutput() }
            }
        copy(local.getInputStream(), client.outputStream)
        upstream.join()
        runCatching { client.close() }
        runCatching { local.close() }
    }

    private fun copy(
        from: InputStream,
        to: OutputStream,
    ) {
        runCatching {
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        }
    }

    @Synchronized
    private fun frontend(): TLSFrontend = frontend ?: Ppnet.newTLSFrontend(targetPort.toLong(), certificates.native).also { frontend = it }

    private fun closeListener() {
        listener?.let { runCatching { it.close() } }
        listener = null
    }

    private companion object {
        const val PREFS = "network_zerotier"
        const val KEY_ENABLED = "enabled"
        const val KEY_NETWORK = "network_id"
        const val HEX = 16
        const val HTTPS_PORT = 443
        const val BACKLOG = 16
        const val BUFFER = 16 * 1024
        const val POLL_MS = 1_000L
        val NETWORK_ID = Regex("[0-9a-f]{16}")
    }
}
