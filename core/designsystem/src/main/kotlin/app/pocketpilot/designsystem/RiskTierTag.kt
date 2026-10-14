package app.pocketpilot.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.core.model.RiskTier

/**
 * A tool's risk tier as a small label. READ and INTERACT are neutral, SENSITIVE uses the secondary
 * container (the "needs approval" role) and DESTRUCTIVE the error container.
 */
@Composable
fun RiskTierTag(
    tier: RiskTier,
    modifier: Modifier = Modifier,
) {
    val (container, content) = riskTierColors(tier)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = riskTierLabel(tier),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The container and content colours of [tier], from the status roles. */
@Composable
fun riskTierColors(tier: RiskTier): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tier) {
        RiskTier.DESTRUCTIVE -> scheme.errorContainer to scheme.onErrorContainer
        RiskTier.SENSITIVE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

/** The tier's name as the owner reads it. */
@Composable
fun riskTierLabel(tier: RiskTier): String =
    stringResource(
        when (tier) {
            RiskTier.READ -> R.string.core_designsystem_tier_read
            RiskTier.INTERACT -> R.string.core_designsystem_tier_interact
            RiskTier.SENSITIVE -> R.string.core_designsystem_tier_sensitive
            RiskTier.DESTRUCTIVE -> R.string.core_designsystem_tier_destructive
        },
    )

@Preview
@Composable
private fun RiskTierTagPreview() {
    PocketPilotTheme {
        Surface {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                RiskTier.entries.forEach { RiskTierTag(it) }
            }
        }
    }
}
