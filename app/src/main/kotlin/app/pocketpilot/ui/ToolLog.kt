package app.pocketpilot.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.core.audit.AuditDecision
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.audit.AuditOutcome
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.designsystem.RiskTierTag
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

/** Which calls a tool log shows. */
internal enum class LogFilter(
    @param:StringRes val label: Int,
) {
    ALL(R.string.log_filter_all),

    /** Calls of tools that act on the phone, not just read it. */
    CHANGES(R.string.log_filter_changes),

    /** Failed and refused calls. */
    ERRORS(R.string.log_filter_errors),
}

/** The events [filter] keeps; [tiers] maps tool names to their risk tier. */
internal fun List<AuditEvent>.filteredBy(
    filter: LogFilter,
    tiers: Map<String, RiskTier>,
): List<AuditEvent> =
    when (filter) {
        LogFilter.ALL -> this
        LogFilter.CHANGES -> filter { (tiers[it.tool] ?: RiskTier.INTERACT) > RiskTier.READ }
        LogFilter.ERRORS -> filter { it.outcome != AuditOutcome.SUCCESS }
    }

/** The All, Changes and Errors filter above a tool log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogFilterRow(
    filter: LogFilter,
    onFilterChange: (LogFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        LogFilter.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = filter == option,
                onClick = { onFilterChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = LogFilter.entries.size),
                label = { Text(stringResource(option.label)) },
            )
        }
    }
}

/** The current time, ticking every second, for relative times and the "call in flight" pulse. */
@Composable
internal fun rememberNow(): State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }

/**
 * One call in a tool log: who called (their initial), the tool in monospace with its outcome, and the
 * risk tier with how long ago. A failed or refused call has an error stripe on its leading edge.
 */
@Composable
internal fun AuditRow(
    event: AuditEvent,
    tier: RiskTier?,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = event.outcome != AuditOutcome.SUCCESS
    val stripe = MaterialTheme.colorScheme.errorContainer
    ListItem(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .drawBehind { if (failed) drawRect(stripe, size = Size(STRIPE_WIDTH.toPx(), size.height)) },
        leadingContent = { PrincipalInitial(event.principal) },
        headlineContent = {
            Text(
                event.tool,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                outcomeSummary(event),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tier?.let { RiskTierTag(it) }
                Text(
                    relativeTime(now - event.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The caller's first letter in a 32 dp circle on the secondary container. */
@Composable
private fun PrincipalInitial(principal: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                principal.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** "Done in 120 ms", "Failed: ELEMENT_NOT_FOUND" or "Refused: DENIED_BY_POLICY". */
@Composable
private fun outcomeSummary(event: AuditEvent): String =
    when (event.outcome) {
        AuditOutcome.SUCCESS -> stringResource(R.string.log_outcome_success, event.principal, event.durationMs)
        AuditOutcome.ERROR -> stringResource(R.string.log_outcome_error, event.principal, event.errorCode ?: "")
        AuditOutcome.DENIED -> stringResource(R.string.log_outcome_denied, event.principal, event.errorCode ?: "")
    }

/** "12 s", "3 min", "2 h" or "4 d". */
@Composable
internal fun relativeTime(ageMillis: Long): String {
    val seconds = (ageMillis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(R.string.log_age_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.log_age_minutes, seconds / 60)
        seconds < 86_400 -> stringResource(R.string.log_age_hours, seconds / 3600)
        else -> stringResource(R.string.log_age_days, seconds / 86_400)
    }
}

/** A call's full record: redacted arguments, the policy decision, outcome, error code and duration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditDetailSheet(
    event: AuditEvent,
    tier: RiskTier?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.tool,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                tier?.let { RiskTierTag(it) }
            }
            DetailLine(R.string.log_detail_caller, event.principal)
            DetailLine(
                R.string.log_detail_time,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(event.timestampMillis)),
            )
            DetailLine(
                R.string.log_detail_decision,
                stringResource(
                    when (event.decision) {
                        AuditDecision.ALLOW -> R.string.log_decision_allow
                        AuditDecision.DENY -> R.string.log_decision_deny
                    },
                ),
            )
            DetailLine(
                R.string.log_detail_outcome,
                stringResource(
                    when (event.outcome) {
                        AuditOutcome.SUCCESS -> R.string.log_result_success
                        AuditOutcome.ERROR -> R.string.log_result_error
                        AuditOutcome.DENIED -> R.string.log_result_denied
                    },
                ),
            )
            event.errorCode?.let { DetailLine(R.string.log_detail_error, it) }
            DetailLine(R.string.log_detail_duration, stringResource(R.string.log_duration_ms, event.durationMs))
            event.backend?.let { DetailLine(R.string.log_detail_backend, it) }
            Text(stringResource(R.string.log_detail_arguments), style = MaterialTheme.typography.labelLarge)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    prettyJson(event.argumentsRedacted),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                )
            }
            Text(
                stringResource(R.string.log_detail_redacted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(
    @StringRes label: Int,
    value: String,
) {
    Row {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private val prettyPrinter = Json { prettyPrint = true }

private fun prettyJson(arguments: JsonObject): String =
    if (arguments.isEmpty()) "{}" else prettyPrinter.encodeToString(JsonObject.serializer(), arguments)

private const val TICK_MS = 1_000L
private val STRIPE_WIDTH = 4.dp
