@file:OptIn(ExperimentalLayoutApi::class)

package app.pocketpilot.feature.network

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.certificates.CertificateState
import app.pocketpilot.network.publicaccess.RelayConfig
import app.pocketpilot.network.publicaccess.RelayNetwork
import app.pocketpilot.network.publicaccess.RelayTarget
import app.pocketpilot.network.wireguard.WireGuardSetup
import app.pocketpilot.network.zerotier.ZeroTierSetup
import java.text.DateFormat
import java.util.Date

/** What the owner can do on [OwnNetworkCard]. Configure calls return a problem in plain words, or null. */
data class OwnNetworkActions(
    val saveCertificate: (domain: String, email: String, token: String) -> Unit,
    val requestCertificate: () -> Unit,
    val setFollowRelay: (Boolean) -> Unit,
    val configureWireGuard: (String) -> String?,
    val setWireGuard: (on: Boolean) -> Unit,
    val forgetWireGuard: () -> Unit,
    val configureZeroTier: (String) -> String?,
    val setZeroTier: (on: Boolean) -> Unit,
    val forgetZeroTier: () -> Unit,
    val openUrl: (String) -> Unit,
    val wireGuardLog: () -> String,
    val zeroTierLog: () -> String,
)

/**
 * WireGuard and ZeroTier with a self-hosted relay (spec section 7): the owner's domain and its
 * certificate, each network's setup and state, and ready-to-paste relay configs.
 */
@Composable
fun OwnNetworkCard(
    certificate: CertificateState,
    wireGuard: NetworkState,
    wireGuardSetup: WireGuardSetup,
    zeroTier: NetworkState,
    zeroTierSetup: ZeroTierSetup,
    actions: OwnNetworkActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_network_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.feature_network_explainer), style = MaterialTheme.typography.bodySmall)
            CertificateSection(certificate, actions)
            HorizontalDivider()
            WireGuardSection(wireGuard, wireGuardSetup, actions)
            HorizontalDivider()
            ZeroTierSection(zeroTier, zeroTierSetup, actions)
        }
    }
}

@Composable
private fun CertificateSection(
    state: CertificateState,
    actions: OwnNetworkActions,
) {
    var domain by rememberSaveable(state.domain) { mutableStateOf(state.domain) }
    var email by rememberSaveable(state.email) { mutableStateOf(state.email) }
    var token by remember { mutableStateOf("") }
    Text(stringResource(R.string.feature_network_cert_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = domain,
        onValueChange = { domain = it },
        label = { Text(stringResource(R.string.feature_network_cert_domain)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.feature_network_cert_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.feature_network_cert_token)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = { if (state.hasToken) Text(stringResource(R.string.feature_network_cert_token_saved)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            actions.saveCertificate(domain, email, token)
            token = ""
        }) { Text(stringResource(R.string.feature_network_cert_save)) }
        Button(
            onClick = actions.requestCertificate,
            enabled = !state.busy && state.domain.isNotEmpty() && state.hasToken,
        ) { Text(stringResource(R.string.feature_network_cert_get)) }
    }
    when {
        state.busy -> Text(stringResource(R.string.feature_network_cert_busy), style = MaterialTheme.typography.bodySmall)
        state.ready -> Text(stringResource(R.string.feature_network_cert_ready, DateFormat.getDateInstance().format(Date(state.notAfter))))
        else -> Text(stringResource(R.string.feature_network_cert_none), style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let { Text(stringResource(R.string.feature_network_cert_error, it), color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.feature_network_cert_follow), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.feature_network_cert_follow_explainer), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.followRelay, onCheckedChange = actions.setFollowRelay)
    }
}

@Composable
private fun WireGuardSection(
    state: NetworkState,
    setup: WireGuardSetup,
    actions: OwnNetworkActions,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var editing by rememberSaveable(setup.configured) { mutableStateOf(!setup.configured) }
    var config by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            config =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull().orEmpty()
        }

    Text(stringResource(R.string.feature_network_wg_title), style = MaterialTheme.typography.titleSmall)
    if (editing) {
        OutlinedTextField(
            value = config,
            onValueChange = { config = it },
            label = { Text(stringResource(R.string.feature_network_wg_paste)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.feature_network_wg_import)) }
            Button(onClick = {
                problem = actions.configureWireGuard(config)
                if (problem == null) {
                    editing = false
                    config = ""
                }
            }, enabled = config.isNotBlank()) { Text(stringResource(R.string.feature_network_wg_save)) }
        }
        problem?.let { Text(stringResource(R.string.feature_network_error, it), color = MaterialTheme.colorScheme.error) }
        return
    }
    StateLine(state)
    setup.publicKey?.let { key ->
        Text(
            stringResource(R.string.feature_network_public_key, key),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    val address = (state as? NetworkState.Connected)?.address
    val peer = if (setup.publicKey != null && address != null) RelayConfig.wireGuardPeer(setup.publicKey, address) else null
    RelaySetups(state, RelayNetwork.WIREGUARD, peer)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnOffButton(state, actions.setWireGuard)
        setup.publicKey?.let { key ->
            TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text(stringResource(R.string.feature_network_copy_key)) }
        }
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.feature_network_wg_replace)) }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(actions.wireGuardLog()))
        }) { Text(stringResource(R.string.feature_network_copy_log)) }
        TextButton(onClick = actions.forgetWireGuard) { Text(stringResource(R.string.feature_network_wg_forget)) }
    }
}

@Composable
private fun ZeroTierSection(
    state: NetworkState,
    setup: ZeroTierSetup,
    actions: OwnNetworkActions,
) {
    val clipboard = LocalClipboardManager.current
    var networkId by rememberSaveable(setup.networkId) { mutableStateOf(setup.networkId) }
    var problem by remember { mutableStateOf<String?>(null) }

    Text(stringResource(R.string.feature_network_zt_title), style = MaterialTheme.typography.titleSmall)
    if (setup.networkId.isEmpty()) {
        OutlinedTextField(
            value = networkId,
            onValueChange = { networkId = it },
            label = { Text(stringResource(R.string.feature_network_zt_network)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { problem = actions.configureZeroTier(networkId) }, enabled = networkId.isNotBlank()) {
            Text(stringResource(R.string.feature_network_zt_save))
        }
        problem?.let { Text(stringResource(R.string.feature_network_error, it), color = MaterialTheme.colorScheme.error) }
        return
    }
    Text(setup.networkId, fontFamily = FontFamily.Monospace)
    if (state is NetworkState.NeedsAuth) {
        Text(stringResource(R.string.feature_network_zt_approve, state.code.orEmpty()))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.openUrl(state.url) }) { Text(stringResource(R.string.feature_network_zt_open)) }
            state.code?.let { code ->
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text(code, fontFamily = FontFamily.Monospace) }
            }
        }
    } else {
        StateLine(state)
        setup.nodeId?.let { Text(stringResource(R.string.feature_network_zt_node, it), style = MaterialTheme.typography.bodySmall) }
    }
    RelaySetups(state, RelayNetwork.ZEROTIER, peer = null)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnOffButton(state, actions.setZeroTier)
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(actions.zeroTierLog()))
        }) { Text(stringResource(R.string.feature_network_copy_log)) }
        TextButton(onClick = actions.forgetZeroTier) { Text(stringResource(R.string.feature_network_zt_forget)) }
    }
}

@Composable
private fun StateLine(state: NetworkState) {
    val clipboard = LocalClipboardManager.current
    when (state) {
        NetworkState.Stopped -> {
            Text(stringResource(R.string.feature_network_off), style = MaterialTheme.typography.bodySmall)
        }

        NetworkState.Connecting, is NetworkState.NeedsAuth -> {
            Text(stringResource(R.string.feature_network_connecting))
        }

        is NetworkState.Connected -> {
            Text(stringResource(R.string.feature_network_connected, state.address))
        }

        is NetworkState.Error -> {
            Text(stringResource(R.string.feature_network_error, state.reason), color = MaterialTheme.colorScheme.error)
            state.details?.let { details ->
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(details)) },
                ) { Text(stringResource(R.string.feature_network_copy_details)) }
            }
        }
    }
}

@Composable
private fun OnOffButton(
    state: NetworkState,
    set: (Boolean) -> Unit,
) {
    if (state == NetworkState.Stopped) {
        Button(onClick = { set(true) }) { Text(stringResource(R.string.feature_network_turn_on)) }
    } else {
        OutlinedButton(onClick = { set(false) }) { Text(stringResource(R.string.feature_network_turn_off)) }
    }
}

/** The connector URL and relay configs, once the phone has an address on the network. */
@Composable
private fun RelaySetups(
    state: NetworkState,
    network: RelayNetwork,
    peer: String?,
) {
    val connected = state as? NetworkState.Connected ?: return
    val target = runCatching { RelayTarget(connected.hostname, connected.address) }.getOrNull() ?: return
    val clipboard = LocalClipboardManager.current
    var more by rememberSaveable { mutableStateOf(false) }
    val url = "https://${target.domain}/mcp"
    Text(stringResource(R.string.feature_network_url), style = MaterialTheme.typography.bodySmall)
    Text(url, fontFamily = FontFamily.Monospace)
    Text(stringResource(R.string.feature_network_router_explainer), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text(stringResource(R.string.feature_network_copy_url)) }
        TextButton(onClick = { clipboard.setText(AnnotatedString(RelayConfig.openWrt(target, network))) }) {
            Text(stringResource(R.string.feature_network_router_setup))
        }
        TextButton(onClick = { more = !more }) { Text(stringResource(R.string.feature_network_more)) }
    }
    if (more) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(RelayConfig.nginx(target)))
            }) { Text(stringResource(R.string.feature_network_copy_nginx)) }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(RelayConfig.caddy(target)))
            }) { Text(stringResource(R.string.feature_network_copy_caddy)) }
            peer?.let { p ->
                TextButton(onClick = { clipboard.setText(AnnotatedString(p)) }) { Text(stringResource(R.string.feature_network_copy_peer)) }
            }
        }
    }
}
