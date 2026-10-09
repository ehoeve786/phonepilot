package app.pocketpilot.server.http

import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.server.mcp.McpServerFactory
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.server.oauth.Lockout
import app.pocketpilot.server.oauth.oauthRoutes
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the MCP endpoint is listening. */
sealed interface ServerState {
    data object Stopped : ServerState

    data class Running(
        val port: Int,
        /** The loopback port network providers forward remote clients to. */
        val remotePort: Int,
    ) : ServerState {
        val url: String get() = "http://$LOOPBACK:$port$MCP_PATH"
    }

    data class Failed(
        val reason: String,
    ) : ServerState
}

/**
 * The Streamable HTTP MCP endpoint at `/mcp` and the OAuth endpoints, bound to 127.0.0.1 only (spec
 * section 5) on two ports: one for apps on the phone, and one that only network providers forward
 * remote clients to. [McpAuth] decides what each port accepts.
 */
class McpHttpServer(
    private val factory: McpServerFactory,
    private val sessions: SessionFactory,
    private val localToken: () -> String,
    private val oauth: AuthorizationServer? = null,
    private val lockout: Lockout = Lockout(),
    /** Host names remote clients use, such as the Tailscale node's `*.ts.net` name. */
    private val remoteHosts: () -> Set<String> = { emptySet() },
) {
    private val state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = state.asStateFlow()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts listening; a no-op when already running. Pass port 0 to pick free ports (tests). */
    @Synchronized
    fun start(
        port: Int = DEFAULT_PORT,
        remotePort: Int = DEFAULT_REMOTE_PORT,
    ) {
        if (engine != null) return
        try {
            val chosenRemote = if (remotePort == 0) freePort() else remotePort
            val server =
                embeddedServer(
                    CIO,
                    configure = {
                        connector {
                            host = LOOPBACK
                            this.port = port
                        }
                        connector {
                            host = LOOPBACK
                            this.port = chosenRemote
                        }
                    },
                ) {
                    install(McpAuth) {
                        localToken = this@McpHttpServer.localToken
                        oauth = this@McpHttpServer.oauth
                        lockout = this@McpHttpServer.lockout
                        sessions = this@McpHttpServer.sessions
                        remoteHosts = this@McpHttpServer.remoteHosts
                        this.remotePort = chosenRemote
                    }
                    oauth?.let { authorizationServer ->
                        routing {
                            oauthRoutes(
                                server = authorizationServer,
                                lockout = lockout,
                                issuer = { call -> call.issuer(chosenRemote) },
                                clientAddress = { call -> call.clientAddress(chosenRemote) },
                            )
                        }
                    }
                    // McpAuth checks Host and Origin for both ports, so the SDK's localhost-only check is off.
                    mcpStreamableHttp(path = MCP_PATH, enableDnsRebindingProtection = false) {
                        factory.create(call.attributes[SessionStarter]())
                    }
                }
            server.start(wait = false)
            engine = server
            val ports = server.resolvedPorts()
            state.value = ServerState.Running(ports.first { it != chosenRemote }, chosenRemote)
        } catch (e: Exception) {
            state.value = ServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = STOP_GRACE_MS, timeoutMillis = STOP_TIMEOUT_MS)
        engine = null
        state.value = ServerState.Stopped
    }

    private fun EmbeddedServer<CIOApplicationEngine, *>.resolvedPorts(): List<Int> =
        kotlinx.coroutines.runBlocking { engine.resolvedConnectors().map { it.port } }

    private fun freePort(): Int = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName(LOOPBACK)).use { it.localPort }

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_REMOTE_PORT = 8766
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L
    }
}

internal const val LOOPBACK = "127.0.0.1"
internal const val MCP_PATH = "/mcp"
