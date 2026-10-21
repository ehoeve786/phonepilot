package app.pocketpilot.feature.automations

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.ActionStep
import app.pocketpilot.core.automation.AutomationCatalog
import app.pocketpilot.core.automation.Describe
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RunMode
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** The risk tier as a small tag, coloured by the status roles of the design spec. */
@Composable
internal fun TierTag(tier: RiskTier) {
    val (container, content) =
        when (tier) {
            RiskTier.READ, RiskTier.INTERACT -> {
                MaterialTheme.colorScheme.surfaceContainerHighest to
                    MaterialTheme.colorScheme.onSurfaceVariant
            }

            RiskTier.SENSITIVE -> {
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }

            RiskTier.DESTRUCTIVE -> {
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
        }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(
                when (tier) {
                    RiskTier.READ -> R.string.feature_automations_tier_read
                    RiskTier.INTERACT -> R.string.feature_automations_tier_interact
                    RiskTier.SENSITIVE -> R.string.feature_automations_tier_sensitive
                    RiskTier.DESTRUCTIVE -> R.string.feature_automations_tier_destructive
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal fun ActionStep.tier(): RiskTier = AutomationCatalog.action(type)?.tier ?: RiskTier.SENSITIVE

/** One rule in the list: When, Only if, Then, its switch, last run, Run and pin. Tap to edit. */
@Composable
fun AutomationRuleCard(
    rule: Rule,
    running: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onAddIcon: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val iconName =
        rule.triggers
            .firstOrNull { it.type == "shortcut" }
            ?.params
            ?.get("name")
            ?.takeIf { it.isNotBlank() }
    LiquidGlassBentoCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (iconName != null) {
                    IconButton(onClick = { onAddIcon(iconName) }) {
                        Icon(
                            painterResource(R.drawable.feature_automations_ic_add),
                            contentDescription = stringResource(R.string.feature_automations_add_icon),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                IconButton(onClick = { context.shareRule(rule) }) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_share),
                        contentDescription = stringResource(R.string.feature_automations_share),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { onPinnedChange(!rule.pinned) }) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_pin),
                        contentDescription =
                            stringResource(
                                if (rule.pinned) R.string.feature_automations_unpin else R.string.feature_automations_pin,
                            ),
                        tint = if (rule.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChange,
                    thumbContent =
                        if (rule.enabled) {
                            {
                                Icon(
                                    painterResource(R.drawable.feature_automations_ic_check),
                                    contentDescription = null,
                                    Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
            RuleSummary(rule)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.lastRun?.let { last ->
                        stringResource(
                            R.string.feature_automations_last_run,
                            DateUtils.getRelativeTimeSpanString(last.atMillis).toString(),
                            outcomeLabel(last.outcome),
                        )
                    } ?: stringResource(R.string.feature_automations_never_ran),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    OutlinedButton(onClick = onStop) {
                        Icon(painterResource(R.drawable.feature_automations_ic_stop), contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.feature_automations_stop), modifier = Modifier.padding(start = 4.dp))
                    }
                } else {
                    FilledTonalButton(onClick = onRun) {
                        Icon(painterResource(R.drawable.feature_automations_ic_play), contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.feature_automations_run), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** A rule as three short sections, shared by the rule card and the proposal card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleSummary(rule: Rule) {
    Section(stringResource(R.string.feature_automations_when)) {
        rule.triggers.forEach { StepText(Describe.trigger(it)) }
    }
    if (!rule.constraints.isEmpty) {
        Section(stringResource(R.string.feature_automations_only_if)) { StepText(Describe.group(rule.constraints)) }
    }
    Section(stringResource(R.string.feature_automations_then)) {
        if (rule.runAs == RunMode.AGENT) {
            StepText(stringResource(R.string.feature_automations_agent_summary, rule.agentPrompt.take(PROMPT_PREVIEW)))
        } else {
            rule.actions.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepText("${i + 1}. ${Describe.action(step)}")
                    val tier = step.tier()
                    if (tier >= RiskTier.SENSITIVE) TierTag(tier)
                }
            }
        }
    }
}

private const val PROMPT_PREVIEW = 80

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** A rule an AI app proposed, with Accept, Edit and Decline. */
@Composable
internal fun ProposalCard(
    rule: Rule,
    /** The name of the rule this proposal changes, when it changes one. */
    replacesName: String?,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onDecline: () -> Unit,
) {
    LiquidGlassBentoCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(rule.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.feature_automations_proposed_by, rule.proposedBy.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (replacesName != null) {
                Text(
                    stringResource(R.string.feature_automations_proposal_replaces, replacesName),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RuleSummary(rule)
            if (rule.actions.any { it.tier() >= RiskTier.SENSITIVE } || rule.runAs == RunMode.AGENT) {
                Text(
                    stringResource(R.string.feature_automations_proposal_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onDecline,
                ) { Text(stringResource(R.string.feature_automations_decline), color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.feature_automations_edit)) }
                Button(onClick = onAccept) { Text(stringResource(R.string.feature_automations_accept)) }
            }
        }
    }
}
