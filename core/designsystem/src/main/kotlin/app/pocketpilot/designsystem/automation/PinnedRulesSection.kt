package app.pocketpilot.designsystem.automation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.designsystem.R
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.PocketPilotTheme

/** An automation rule the owner pinned to Home. */
data class PinnedRuleUi(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

/** Home's pinned automations: up to [MAX_PINNED] rules with a switch each, and a way to all rules. */
@Composable
fun PinnedRulesSection(
    rules: List<PinnedRuleUi>,
    onToggle: (String, Boolean) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidGlassBentoCard(modifier, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.core_designsystem_pinned_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.core_designsystem_pinned_see_all)) }
        }
        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.core_designsystem_pinned_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
        rules.take(MAX_PINNED).forEach { rule ->
            ListItem(
                headlineContent = { Text(rule.name) },
                leadingContent = {
                    Icon(
                        painterResource(R.drawable.core_designsystem_ic_bolt),
                        contentDescription = null,
                        tint =
                            if (rule.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onToggle(rule.id, it) },
                        thumbContent =
                            if (rule.enabled) {
                                {
                                    Icon(
                                        painterResource(R.drawable.core_designsystem_ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** Home shows at most this many pinned rules; the rest are in Automations. */
const val MAX_PINNED = 4

@Preview
@Composable
private fun PinnedRulesSectionPreview() {
    PocketPilotTheme {
        Surface {
            PinnedRulesSection(
                rules =
                    listOf(
                        PinnedRuleUi("r1", "Quiet at work", enabled = true),
                        PinnedRuleUi("r2", "Timer when I start cooking", enabled = false),
                    ),
                onToggle = { _, _ -> },
                onSeeAll = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
