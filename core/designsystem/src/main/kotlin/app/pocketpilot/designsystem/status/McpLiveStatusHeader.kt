package app.pocketpilot.designsystem.status

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.designsystem.PocketPilotTheme

/** Whether the MCP server is off, on with nobody connected, or serving clients. */
enum class ServerPhase { Stopped, Idle, Running }

/** A client using the server now, such as "Claude (claude.ai)". */
data class ConnectedClient(
    val id: String,
    val name: String,
)

/** A remote access network, such as Tailscale or WireGuard, and whether it is up. */
data class RemoteLink(
    val name: String,
    val up: Boolean,
)

/** Everything the status header shows. */
data class McpStatus(
    val phase: ServerPhase,
    /** The URL clients connect to, or why the server is not running. */
    val endpoint: String,
    val clients: List<ConnectedClient>,
    val remote: List<RemoteLink>,
    /** True while a tool call is running; the status dot pulses. */
    val callInFlight: Boolean,
)

/** Home's top block: server state, endpoint, connected clients, remote access and the on/off switch. */
@Composable
fun McpLiveStatusHeader(
    status: McpStatus,
    onServerChange: (Boolean) -> Unit,
    onCopyEndpoint: () -> Unit,
    onClientClick: (ConnectedClient) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = status.phase != ServerPhase.Stopped
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.phase, pulsing = status.callInFlight)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = headline(status),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status.endpoint,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopyEndpoint, enabled = running) {
                    Icon(painterResource(R.drawable.core_designsystem_ic_copy), contentDescription = stringResource(R.string.core_designsystem_status_copy))
                }
            }
            if (status.clients.isNotEmpty() || status.remote.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status.clients.forEach { client ->
                        AssistChip(
                            onClick = { onClientClick(client) },
                            label = { Text(client.name) },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.core_designsystem_ic_link),
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            shape = MaterialTheme.shapes.small,
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            border = null,
                        )
                    }
                    status.remote.forEach { link ->
                        AssistChip(
                            onClick = {},
                            enabled = link.up,
                            label = {
                                Text(if (link.up) link.name else stringResource(R.string.core_designsystem_status_remote_off, link.name))
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.core_designsystem_ic_lock),
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
            val switchState =
                stringResource(if (running) R.string.core_designsystem_status_server_on else R.string.core_designsystem_status_server_off)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { stateDescription = switchState },
            ) {
                Text(
                    stringResource(R.string.core_designsystem_status_server),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = running, onCheckedChange = onServerChange)
            }
        }
    }
}

@Composable
private fun StatusDot(
    phase: ServerPhase,
    pulsing: Boolean,
) {
    val color =
        when (phase) {
            ServerPhase.Running -> MaterialTheme.colorScheme.primary
            ServerPhase.Idle -> MaterialTheme.colorScheme.tertiary
            ServerPhase.Stopped -> MaterialTheme.colorScheme.outline
        }
    val alpha by rememberInfiniteTransition(label = "status").animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) PULSE_LOW else 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulse",
    )
    val description = if (pulsing) stringResource(R.string.core_designsystem_status_acting) else phase.name
    Box(
        Modifier
            .size(12.dp)
            .alpha(alpha)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun headline(status: McpStatus): String =
    when (status.phase) {
        ServerPhase.Stopped -> {
            stringResource(R.string.core_designsystem_status_stopped)
        }

        ServerPhase.Idle -> {
            stringResource(R.string.core_designsystem_status_idle)
        }

        ServerPhase.Running -> {
            when (val n = status.clients.size) {
                1 -> stringResource(R.string.core_designsystem_status_running_one)
                else -> stringResource(R.string.core_designsystem_status_running_many, n)
            }
        }
    }

private const val PULSE_LOW = 0.35f
private const val PULSE_MS = 700

@Preview
@Composable
private fun McpLiveStatusHeaderPreview() {
    PocketPilotTheme {
        McpLiveStatusHeader(
            status =
                McpStatus(
                    phase = ServerPhase.Running,
                    endpoint = "https://phone.example.com/mcp",
                    clients = listOf(ConnectedClient("c1", "Claude (claude.ai)")),
                    remote = listOf(RemoteLink("WireGuard", up = true), RemoteLink("Tailscale", up = false)),
                    callInFlight = true,
                ),
            onServerChange = {},
            onCopyEndpoint = {},
            onClientClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
