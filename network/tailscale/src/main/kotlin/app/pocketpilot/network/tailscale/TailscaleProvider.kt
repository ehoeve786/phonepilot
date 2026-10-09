package app.pocketpilot.network.tailscale

import android.content.Context
import android.os.Build
import app.pocketpilot.network.api.NetworkProvider
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccess
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.network.tailscale.gen.ppnet.Node
import app.pocketpilot.network.tailscale.gen.ppnet.Ppnet
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
import java.io.File

/**
 * Tailscale through an embedded tsnet node (spec section 7). The node joins the owner's tailnet as
 * `pocketpilot-<model>`, terminates HTTPS with its `*.ts.net` certificate and forwards each request to
 * [targetPort] on 127.0.0.1. Public access is Tailscale Funnel on port 443.
 *
 * Whether Tailscale and Funnel are on survives restarts, so the foreground service can call [resume].
 */
class TailscaleProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val targetPort: Int,
) : NetworkProvider,
    PublicAccess {
    override val id: String = "tailscale"

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val node: Node by lazy {
        go.Seq.setContext(context.applicationContext)
        Ppnet.newNode(File(context.noBackupFilesDir, "tailscale").absolutePath, hostname(), targetPort.toLong())
    }

    private val mutableState = MutableStateFlow<NetworkState>(NetworkState.Stopped)
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()

    private val mutablePublic = MutableStateFlow(PublicAccessState(enabled = prefs.getBoolean(KEY_PUBLIC, false)))
    override val publicState: StateFlow<PublicAccessState> = mutablePublic.asStateFlow()

    /** Whether the owner turned Tailscale on. */
    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    private var poller: Job? = null

    /** Starts the node if the owner left it on. */
    fun resume() {
        if (enabled) start()
    }

    @Synchronized
    override fun start() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        if (poller?.isActive == true) return
        mutableState.value = NetworkState.Connecting
        poller =
            scope.launch(Dispatchers.IO) {
                try {
                    node.setPublic(mutablePublic.value.enabled)
                    node.start("")
                } catch (e: Exception) {
                    mutableState.value = NetworkState.Error(e.message ?: "Tailscale could not start")
                    return@launch
                }
                while (isActive) {
                    refresh()
                    delay(if (state.value is NetworkState.Connected) POLL_CONNECTED_MS else POLL_STARTING_MS)
                }
            }
    }

    @Synchronized
    override fun stop() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        poller?.cancel()
        poller = null
        scope.launch(Dispatchers.IO) { node.stop() }
        mutableState.value = NetworkState.Stopped
        mutablePublic.value = mutablePublic.value.copy(url = null, problem = null)
    }

    /** Stops the node when the foreground service ends, but keeps it on for the next [resume]. */
    @Synchronized
    fun pause() {
        if (poller == null) return
        poller?.cancel()
        poller = null
        scope.launch(Dispatchers.IO) { node.stop() }
        mutableState.value = NetworkState.Stopped
    }

    override fun logout() {
        stop()
        scope.launch(Dispatchers.IO) { node.logout() }
    }

    override fun setPublic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLIC, enabled).apply()
        mutablePublic.value = mutablePublic.value.copy(enabled = enabled, url = null, problem = null)
        scope.launch(Dispatchers.IO) {
            runCatching { node.setPublic(enabled) }
            refresh()
        }
    }

    /** The node's recent log lines, for Doctor. */
    fun logs(): String = runCatching { node.logs() }.getOrDefault("")

    private fun refresh() {
        val status = runCatching { JSONObject(node.status()) }.getOrNull() ?: return
        val backend = status.optString("backendState")
        val dnsName = status.optString("dnsName")
        val error = status.optString("error").takeIf(String::isNotEmpty)
        val ips = status.optJSONArray("ips")
        mutableState.value =
            when {
                !status.optBoolean("running") -> {
                    NetworkState.Stopped
                }

                backend == "NeedsLogin" && status.optString("authUrl").isNotEmpty() -> {
                    NetworkState.NeedsAuth(status.optString("authUrl"))
                }

                backend == "NeedsMachineAuth" -> {
                    NetworkState.Error("Approve this phone in the Tailscale admin console, under Machines")
                }

                backend == "Running" && dnsName.isNotEmpty() -> {
                    NetworkState.Connected(ips?.optString(0).orEmpty(), dnsName)
                }

                error != null -> {
                    NetworkState.Error(error)
                }

                else -> {
                    NetworkState.Connecting
                }
            }
        val public = status.optBoolean("public")
        val problem = status.optString("funnelProblem").takeIf(String::isNotEmpty) ?: error.takeIf { public }
        mutablePublic.value =
            PublicAccessState(
                enabled = public,
                url = if (public && problem == null && status.optBoolean("serving")) "https://$dnsName/mcp" else null,
                problem = if (public) problem else null,
            )
    }

    private companion object {
        const val PREFS = "network_tailscale"
        const val KEY_ENABLED = "enabled"
        const val KEY_PUBLIC = "public"
        const val POLL_STARTING_MS = 1_000L
        const val POLL_CONNECTED_MS = 5_000L

        /** `pocketpilot-sm-s918w`: Tailscale host names allow letters, digits and hyphens. */
        fun hostname(): String =
            "pocketpilot-" +
                Build.MODEL
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(MAX_MODEL_LENGTH)

        const val MAX_MODEL_LENGTH = 40
    }
}
