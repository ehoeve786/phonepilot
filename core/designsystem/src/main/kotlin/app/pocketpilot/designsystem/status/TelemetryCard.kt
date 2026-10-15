package app.pocketpilot.designsystem.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.PocketPilotTheme

/** One reading, such as "Battery" and "82%, charging"; [fraction] adds a bar under it. */
data class TelemetryItemUi(
    val label: String,
    val value: String,
    val fraction: Float? = null,
)

/** A few live numbers about the phone and the server. */
@Composable
fun TelemetryCard(
    items: List<TelemetryItemUi>,
    modifier: Modifier = Modifier,
) {
    LiquidGlassBentoCard(modifier, title = stringResource(R.string.core_designsystem_telemetry_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Column {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.value, style = MaterialTheme.typography.titleSmall)
                    item.fraction?.let { fraction ->
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun TelemetryCardPreview() {
    PocketPilotTheme {
        TelemetryCard(
            items =
                listOf(
                    TelemetryItemUi("Battery", "82%, charging", 0.82f),
                    TelemetryItemUi("Network", "Wi-Fi"),
                    TelemetryItemUi("Tool calls, last hour", "12"),
                ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
