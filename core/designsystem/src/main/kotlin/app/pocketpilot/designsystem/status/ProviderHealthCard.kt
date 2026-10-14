package app.pocketpilot.designsystem.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.PocketPilotTheme

/** How a model in the owner's chain is doing. */
enum class ProviderLight { Ready, Limited, Down, Unknown }

/** One row of the provider card: the model's name, its light and a short word on why, such as "Back in 40 s". */
data class ProviderHealthUi(
    val name: String,
    val light: ProviderLight,
    val detail: String? = null,
)

/** Agent mode's models in the order a run falls back through them, each with a coloured light. */
@Composable
fun ProviderHealthCard(
    providers: List<ProviderHealthUi>,
    modifier: Modifier = Modifier,
) {
    LiquidGlassBentoCard(modifier, title = stringResource(R.string.core_designsystem_providers_title)) {
        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.core_designsystem_providers_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { ProviderRow(it) }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderHealthUi) {
    val word = stringResource(provider.light.label())
    val summary = listOfNotNull(provider.name, word, provider.detail).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = summary },
    ) {
        Box(Modifier.size(10.dp).background(provider.light.color(), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                provider.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                provider.detail ?: word,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderLight.color() =
    when (this) {
        ProviderLight.Ready -> MaterialTheme.colorScheme.primary
        ProviderLight.Limited -> MaterialTheme.colorScheme.tertiary
        ProviderLight.Down -> MaterialTheme.colorScheme.error
        ProviderLight.Unknown -> MaterialTheme.colorScheme.outline
    }

private fun ProviderLight.label() =
    when (this) {
        ProviderLight.Ready -> R.string.core_designsystem_providers_ready
        ProviderLight.Limited -> R.string.core_designsystem_providers_limited
        ProviderLight.Down -> R.string.core_designsystem_providers_down
        ProviderLight.Unknown -> R.string.core_designsystem_providers_unknown
    }

@Preview
@Composable
private fun ProviderHealthCardPreview() {
    PocketPilotTheme {
        ProviderHealthCard(
            providers =
                listOf(
                    ProviderHealthUi("Gemini", ProviderLight.Limited, "Back in 40 s"),
                    ProviderHealthUi("Groq", ProviderLight.Ready),
                    ProviderHealthUi("Mistral", ProviderLight.Unknown),
                ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
