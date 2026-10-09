package app.pocketpilot.server.http

import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.server.mcp.McpServerFactory
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the MCP endpoint is listening. */
sealed interface ServerState {
    data object Stopped : ServerState

    data class Running(
        val port: Int,
    ) : ServerState {
        val url: String get() = "http://$LOOPBACK:$port$MCP_PATH"
    }

    data class Failed(
        val reason: String,
    ) : ServerState
}

/**
 * The Streamable HTTP MCP endpoint at `/mcp`, bound to 127.0.0.1 only (spec section 5). Remote access
 * arrives later through network providers that forward to this loopback port.
 */
class McpHttpServer(
    private val factory: McpServerFactory,
    private val sessions: SessionFactory,
    private val localToken: () -> String,
) {
    private val state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = state.asStateFlow()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts listening; a no-op when already running. Pass port 0 to pick a free port (tests). */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (engine != null) return
        try {
            val server =
                embeddedServer(CIO, host = LOOPBACK, port = port) {
                    install(LocalTokenAuth) { token = localToken }
                    mcpStreamableHttp(path = MCP_PATH) { factory.create(sessions.localTokenSession()) }
                }
            server.start(wait = false)
            engine = server
            state.value = ServerState.Running(server.resolvedPort())
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

    private fun EmbeddedServer<CIOApplicationEngine, *>.resolvedPort(): Int =
        kotlinx.coroutines.runBlocking { engine.resolvedConnectors().first().port }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L
    }
}

internal const val LOOPBACK = "127.0.0.1"
internal const val MCP_PATH = "/mcp"
