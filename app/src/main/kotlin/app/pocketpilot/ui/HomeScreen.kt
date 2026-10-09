package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.core.model.PolicyProfile

/** M1 placeholder: proves the app shell, theme, DI and flavor wiring all work. */
@Composable
fun HomeScreen(
    policyProfile: PolicyProfile,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.home_build, policyProfile.flavor.name.lowercase(), versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (policyProfile.shellExecAvailable) R.string.home_shell_available else R.string.home_shell_unavailable,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    PocketPilotTheme { HomeScreen(policyProfile = PolicyProfile.OSS, versionName = "0.1.0") }
}
