package app.pocketpilot.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.policy.ToolMode
import app.pocketpilot.core.policy.ToolModes
import app.pocketpilot.designsystem.RiskTierTag
import app.pocketpilot.designsystem.riskTierLabel
import app.pocketpilot.core.designsystem.R as DsR

/** What the Tools tab shows. */
internal class ToolsTabState(
    val tools: List<ToolSpec>,
    /** The owner's chosen modes; tools missing here keep their tier's default. */
    val modes: Map<String, ToolMode>,
    val auditEvents: List<AuditEvent>,
    /** The deny list: apps PocketPilot never reads or acts in. */
    val protectedApps: List<String>,
    /** False in the Play build, which has no shell tool. */
    val shellAvailable: Boolean,
    val shellEnabled: Boolean,
)

/**
 * Every tool the server exposes, grouped and searchable, each with Allow, Ask or Block; then the
 * protected apps and the shell switch.
 */
@Composable
internal fun ToolsTab(
    state: ToolsTabState,
    onModeChange: (String, ToolMode?) -> Unit,
    onShellChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tierFilter by rememberSaveable { mutableStateOf<RiskTier?>(null) }
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var openedTool by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }

    fun modeOf(spec: ToolSpec): ToolMode = ToolModes.effectiveMode({ state.modes[it] }, spec)

    val groups =
        state.tools
            .asSequence()
            .filter { tierFilter == null || it.riskTier == tierFilter }
            .filter { !blockedOnly || modeOf(it) == ToolMode.BLOCK }
            .filter {
                query.isBlank() ||
                    it.name.contains(query.trim(), ignoreCase = true) ||
                    it.title.contains(query.trim(), ignoreCase = true)
            }.sortedBy { it.name }
            .groupBy { toolGroup(it) }
            .toSortedMap()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tools_search)) },
                leadingIcon = { Icon(painterResource(DsR.drawable.core_designsystem_ic_search), contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RiskTier.entries.forEach { tier ->
                    FilterChip(
                        selected = tierFilter == tier,
                        onClick = { tierFilter = if (tierFilter == tier) null else tier },
                        label = { Text(riskTierLabel(tier)) },
                    )
                }
                FilterChip(
                    selected = blockedOnly,
                    onClick = { blockedOnly = !blockedOnly },
                    label = { Text(stringResource(R.string.tools_filter_blocked)) },
                )
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.tools_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { (group, specs) ->
            item(key = "group-${group.name}") {
                ToolGroupSection(group, specs, ::modeOf) { openedTool = it.name }
            }
        }
        item(key = "protected") {
            ProtectedAppsCard(state.protectedApps, showAllApps) { showAllApps = !showAllApps }
        }
        if (state.shellAvailable) {
            item(key = "shell") { ShellCard(state.shellEnabled, onShellChange) }
        }
    }

    val opened = openedTool?.let { name -> state.tools.firstOrNull { it.name == name } }
    if (opened != null) {
        ToolSheet(
            spec = opened,
            mode = modeOf(opened),
            chosen = state.modes[opened.name],
            recent = remember(state.auditEvents, opened.name) { state.auditEvents.filter { it.tool == opened.name }.take(RECENT_CALLS) },
            onModeChange = { onModeChange(opened.name, it) },
            onDismiss = { openedTool = null },
        )
    }
}

@Composable
private fun ToolGroupSection(
    group: ToolGroup,
    specs: List<ToolSpec>,
    modeOf: (ToolSpec) -> ToolMode,
    onOpen: (ToolSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(group.label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                specs.forEach { spec ->
                    val mode = modeOf(spec)
                    ListItem(
                        modifier = Modifier.clickable { onOpen(spec) },
                        leadingContent = { TierIcon(spec.riskTier) },
                        headlineContent = { Text(spec.title) },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    spec.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                RiskTierTag(spec.riskTier)
                            }
                        },
                        trailingContent = {
                            Text(
                                stringResource(mode.label),
                                style = MaterialTheme.typography.labelLarge,
                                color =
                                    if (mode == ToolMode.BLOCK) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
    }
}

/** A shield for SENSITIVE, a warning for DESTRUCTIVE, and the wrench for the rest. */
@Composable
private fun TierIcon(tier: RiskTier) {
    val (icon, tint) =
        when (tier) {
            RiskTier.DESTRUCTIVE -> DsR.drawable.core_designsystem_ic_warning to MaterialTheme.colorScheme.error
            RiskTier.SENSITIVE -> DsR.drawable.core_designsystem_ic_shield to MaterialTheme.colorScheme.secondary
            else -> DsR.drawable.core_designsystem_ic_build to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Icon(painterResource(icon), contentDescription = riskTierLabel(tier), tint = tint)
}

/** A tool's description, its Allow, Ask or Block choice capped by its tier, and its last calls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolSheet(
    spec: ToolSpec,
    mode: ToolMode,
    /** The owner's explicit choice, or null when the tool runs at its tier's default. */
    chosen: ToolMode?,
    recent: List<AuditEvent>,
    onModeChange: (ToolMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    val allowed = ToolModes.allowedModes(spec)
    val now by rememberNow()
    var opened by remember { mutableStateOf<AuditEvent?>(null) }
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
                Column(Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RiskTierTag(spec.riskTier)
            }
            Text(spec.description, style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ToolMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onModeChange(option.takeUnless { it == ToolModes.defaultMode(spec) }) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ToolMode.entries.size),
                        enabled = option in allowed,
                        label = { Text(stringResource(option.label)) },
                    )
                }
            }
            Text(
                stringResource(modeReason(spec, mode, allowed)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (chosen != null) {
                TextButton(onClick = { onModeChange(null) }) { Text(stringResource(R.string.tools_use_default)) }
            }
            Text(stringResource(R.string.tools_recent_calls), style = MaterialTheme.typography.titleSmall)
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.tools_no_calls),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recent.forEach { event -> AuditRow(event, spec.riskTier, now, onClick = { opened = event }) }
        }
    }
    opened?.let { event -> AuditDetailSheet(event, spec.riskTier) { opened = null } }
}

/** One line under the choice: why Allow is off, or what the current mode means. */
@StringRes
private fun modeReason(
    spec: ToolSpec,
    mode: ToolMode,
    allowed: Set<ToolMode>,
): Int =
    when {
        ToolMode.ALLOW !in allowed && spec.riskTier == RiskTier.DESTRUCTIVE -> R.string.tools_reason_destructive
        ToolMode.ALLOW !in allowed -> R.string.tools_reason_shell
        mode == ToolMode.BLOCK -> R.string.tools_mode_block_explainer
        mode == ToolMode.ASK -> R.string.tools_mode_ask_explainer
        else -> R.string.tools_mode_allow_explainer
    }

@Composable
private fun ProtectedAppsCard(
    apps: List<String>,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(DsR.drawable.core_designsystem_ic_lock), contentDescription = null)
                Text(stringResource(R.string.tools_protected_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.tools_protected_explainer), style = MaterialTheme.typography.bodySmall)
            val shown = if (showAll) apps else apps.take(PROTECTED_PREVIEW)
            shown.forEach { pkg ->
                Text(pkg, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
            }
            if (apps.size > PROTECTED_PREVIEW) {
                TextButton(onClick = onToggleShowAll) {
                    Text(
                        if (showAll) {
                            stringResource(R.string.tools_protected_show_less)
                        } else {
                            stringResource(R.string.tools_protected_show_all, apps.size)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShellCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.shell_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text(stringResource(R.string.shell_explainer), style = MaterialTheme.typography.bodySmall)
            if (enabled) {
                Text(
                    stringResource(R.string.shell_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The owner's name for a mode. */
@get:StringRes
private val ToolMode.label: Int
    get() =
        when (this) {
            ToolMode.ALLOW -> R.string.tools_mode_allow
            ToolMode.ASK -> R.string.tools_mode_ask
            ToolMode.BLOCK -> R.string.tools_mode_block
        }

/** The Tools tab's sections, in display order, with plain names instead of tool namespaces. */
private enum class ToolGroup(
    @param:StringRes val label: Int,
) {
    SCREEN(R.string.tools_group_screen),
    INPUT(R.string.tools_group_input),
    APPS(R.string.tools_group_apps),
    INTENTS(R.string.tools_group_intents),
    SETTINGS(R.string.tools_group_settings),
    NOTIFICATIONS(R.string.tools_group_notifications),
    FILES(R.string.tools_group_files),
    DIAGNOSTICS(R.string.tools_group_diagnostics),
    SHELL(R.string.tools_group_shell),
    OTHER(R.string.tools_group_other),
}

private fun toolGroup(spec: ToolSpec): ToolGroup =
    when {
        spec.name in INTENT_TOOLS -> {
            ToolGroup.INTENTS
        }

        else -> {
            when (spec.namespace) {
                "screen" -> ToolGroup.SCREEN
                "ui" -> ToolGroup.INPUT
                "app" -> ToolGroup.APPS
                "settings" -> ToolGroup.SETTINGS
                "notify" -> ToolGroup.NOTIFICATIONS
                "files" -> ToolGroup.FILES
                "adb", "device" -> ToolGroup.DIAGNOSTICS
                "shell" -> ToolGroup.SHELL
                else -> ToolGroup.OTHER
            }
        }
    }

private val INTENT_TOOLS = setOf("app.start_activity", "app.send_broadcast", "app.open_url", "app.activities", "app.directions")
private const val RECENT_CALLS = 5
private const val PROTECTED_PREVIEW = 6
