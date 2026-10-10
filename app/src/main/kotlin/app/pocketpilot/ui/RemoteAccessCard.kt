package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccessState

/**
 * Remote access over Tailscale (spec section 7): join the tailnet, then optionally open the phone to
 * the internet with Funnel so cloud clients such as claude.ai can reach it. Every remote request needs
 * an OAuth token the owner approved on this phone.
 */
@Composable
internal fun RemoteAccessCard(
    state: NetworkState,
    public: PublicAccessState,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: () -> Unit,
    onPublicChange: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.titleMedium)
            when (state) {
                NetworkState.Stopped -> {
                    Text(stringResource(R.string.remote_off), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onTurnOn) { Text(stringResource(R.string.remote_turn_on)) }
                }

                NetworkState.Connecting -> {
                    Text(stringResource(R.string.remote_connecting))
                }

                is NetworkState.NeedsAuth -> {
                    Text(stringResource(R.string.remote_needs_login), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onSignIn(state.url) }) { Text(stringResource(R.string.remote_sign_in)) }
                }

                is NetworkState.Error -> {
                    Text(stringResource(R.string.remote_error, state.reason), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTurnOff) { Text(stringResource(R.string.remote_turn_off)) }
                        state.details?.let { details ->
                            TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) {
                                Text(stringResource(R.string.remote_copy_details))
                            }
                        }
                    }
                }

                is NetworkState.Connected -> {
                    Text(stringResource(R.string.remote_connected, state.hostname))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.remote_public), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.remote_public_explainer), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = public.enabled, onCheckedChange = onPublicChange)
                    }
                    public.problem?.let {
                        Text(stringResource(R.string.remote_public_problem, it), color = MaterialTheme.colorScheme.error)
                    }
                    if (public.enabled && public.problem == null && public.url == null) {
                        Text(stringResource(R.string.remote_public_starting), style = MaterialTheme.typography.bodySmall)
                    }
                    public.url?.let { url ->
                        Text(stringResource(R.string.remote_public_url), style = MaterialTheme.typography.bodySmall)
                        Text(url, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text(stringResource(R.string.remote_copy_url)) }
                        Text(stringResource(R.string.remote_claude_steps), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTurnOff) { Text(stringResource(R.string.remote_turn_off)) }
                        TextButton(onClick = onSignOut) { Text(stringResource(R.string.remote_sign_out)) }
                    }
                }
            }
        }
    }
}
