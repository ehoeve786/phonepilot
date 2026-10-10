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

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stateDir = File(context.noBackupFilesDir, "tailscale")

    /** Created on first [start]: loading the Go library is the first thing that can fail. */
    @Volatile private var createdNode: Node? = null

    private val mutableState = MutableStateFlow(previousCrash())
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
                    val node = node()
                    node.setPublic(mutablePublic.value.enabled)
                    node.start("")
                } catch (e: Throwable) {
                    // Errors too: a missing native library is an UnsatisfiedLinkError.
                    mutableState.value = NetworkState.Error(e.message ?: e.toString(), e.stackTraceToString())
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
        scope.launch(Dispatchers.IO) { runCatching { createdNode?.stop() } }
        mutableState.value = NetworkState.Stopped
        mutablePublic.value = mutablePublic.value.copy(url = null, problem = null)
    }

    /** Stops the node when the foreground service ends, but keeps it on for the next [resume]. */
    @Synchronized
    fun pause() {
        if (poller == null) return
        poller?.cancel()
        poller = null
        scope.launch(Dispatchers.IO) { runCatching { createdNode?.stop() } }
        mutableState.value = NetworkState.Stopped
    }

    override fun logout() {
        stop()
        scope.launch(Dispatchers.IO) { runCatching { createdNode?.logout() } }
    }

    override fun setPublic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLIC, enabled).apply()
        mutablePublic.value = mutablePublic.value.copy(enabled = enabled, url = null, problem = null)
        scope.launch(Dispatchers.IO) {
            runCatching { createdNode?.setPublic(enabled) }
            refresh()
        }
    }

    /** The node's recent log lines, for Doctor. */
    fun logs(): String = runCatching { createdNode?.logs() }.getOrNull().orEmpty()

    @Synchronized
    private fun node(): Node =
        createdNode ?: run {
            go.Seq.setContext(appContext)
            Ppnet.newNode(stateDir.absolutePath, hostname(), targetPort.toLong()).also { createdNode = it }
        }

    /**
     * If the Go runtime died in the last run, it left a report in the state directory. Shows it once,
     * and leaves Tailscale off so [resume] does not crash the app again on every launch.
     */
    private fun previousCrash(): NetworkState {
        val crash = runCatching { File(stateDir, CRASH_FILE).readText() }.getOrNull()
        if (crash.isNullOrBlank()) return NetworkState.Stopped
        val log = runCatching { File(stateDir, LOG_FILE).readLines().takeLast(CRASH_LOG_LINES) }.getOrDefault(emptyList())
        runCatching { File(stateDir, CRASH_FILE).writeText("") }
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        val details = crash.trim() + "\n\n--- Tailscale log ---\n" + log.joinToString("\n")
        return NetworkState.Error("Tailscale stopped unexpectedly last time", details)
    }

    private fun refresh() {
        val node = createdNode ?: return
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
        const val CRASH_LOG_LINES = 80

        // ppnet.CrashFile and ppnet.LogFile. Kept here so reading them never loads the Go library.
        const val CRASH_FILE = "crash.txt"
        const val LOG_FILE = "node.log"

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
