package app.pocketpilot.feature.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** One connected client. */
data class ClientRow(
    val id: String,
    val name: String,
    val redirectHost: String,
    val scopes: List<String>,
    val lastUsedAtMillis: Long?,
)

/**
 * Remote clients the owner has approved (spec section 8), each removable on its own, plus the kill
 * switch that ends every client's access and turns public access off.
 */
@Composable
fun ClientsCard(
    clients: List<ClientRow>,
    onRevoke: (String) -> Unit,
    onKillSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_clients_title), style = MaterialTheme.typography.titleMedium)
            if (clients.isEmpty()) {
                Text(stringResource(R.string.feature_clients_none), style = MaterialTheme.typography.bodySmall)
            }
            clients.forEach { client ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${client.name} (${client.redirectHost})", style = MaterialTheme.typography.bodyLarge)
                        Text(client.scopes.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        client.lastUsedAtMillis?.let {
                            Text(
                                stringResource(R.string.feature_clients_last_used, DateFormat.getDateTimeInstance().format(Date(it))),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(onClick = { onRevoke(client.id) }) { Text(stringResource(R.string.feature_clients_remove)) }
                }
            }
            OutlinedButton(onClick = onKillSwitch) { Text(stringResource(R.string.feature_clients_kill_switch)) }
            Text(stringResource(R.string.feature_clients_kill_switch_explainer), style = MaterialTheme.typography.bodySmall)
        }
    }
}
