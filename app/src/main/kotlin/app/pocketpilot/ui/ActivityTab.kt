package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** Activity: the full audit log, and Agent mode with its runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityTab(
    auditEvents: List<AuditEvent>,
    tiers: Map<String, RiskTier>,
    agentCard: @Composable () -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == LOG_TAB, onClick = { onTab(LOG_TAB) }, text = { Text(stringResource(R.string.activity_log)) })
            Tab(
                selected = tab == ACTIVITY_AGENT_TAB,
                onClick = { onTab(ACTIVITY_AGENT_TAB) },
                text = { Text(stringResource(R.string.activity_agent)) },
            )
        }
        when (tab) {
            LOG_TAB -> {
                FullLog(auditEvents, tiers)
            }

            else -> {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    agentCard()
                }
            }
        }
    }
}

@Composable
private fun FullLog(
    events: List<AuditEvent>,
    tiers: Map<String, RiskTier>,
) {
    val now by rememberNow()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var opened by remember { mutableStateOf<AuditEvent?>(null) }
    val shown = events.filteredBy(filter, tiers)
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            LogFilterRow(filter, { filter = it }, Modifier.padding(16.dp))
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(shown, key = { it.id }) { event ->
            LiquidGlassBentoCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                AuditRow(event, tiers[event.tool], now, onClick = { opened = event })
            }
        }
    }
    opened?.let { event -> AuditDetailSheet(event, tiers[event.tool]) { opened = null } }
}

private const val LOG_TAB = 0

/** The Agent tab, which the Chat screen opens for its model list. */
internal const val ACTIVITY_AGENT_TAB = 1
