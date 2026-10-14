package app.pocketpilot.feature.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pocketpilot.agent.runtime.AgentProfile
import app.pocketpilot.agent.runtime.ChatItem
import app.pocketpilot.agent.runtime.RunState
import app.pocketpilot.agent.runtime.RunStatus
import app.pocketpilot.agent.runtime.chatItems
import app.pocketpilot.agent.runtime.stepLine
import app.pocketpilot.agent.runtime.transcript
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.agent.AgentCommandBar
import java.text.DateFormat
import java.util.Date

/** What the owner can do in [AgentChat]. */
data class ChatActions(
    /** Starts a new chat with its first message. See [AgentActions] for the two switches. */
    val start: (text: String, profileId: String, allowSettings: Boolean, useFallbacks: Boolean) -> Unit,
    /** The next message in the waiting chat; false when the chat has ended. */
    val send: (String) -> Boolean,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val stop: () -> Unit,
    /** Closes the current chat, stopping it if it is working. */
    val endChat: () -> Unit,
    /** Opens the model list, under Activity, Agent. */
    val openModels: () -> Unit,
)

/**
 * Agent mode as a chat: the owner writes, the agent answers or works on the phone and answers, and
 * each answer waits for the next message. Earlier chats open read-only.
 */
@Composable
fun AgentChat(
    profiles: List<AgentProfile>,
    current: RunState?,
    history: List<RunState>,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    /** The chat the owner put away with New chat; it stays put away until another starts. */
    var hiddenId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var allowSettings by rememberSaveable { mutableStateOf(false) }
    var useFallbacks by rememberSaveable { mutableStateOf(true) }

    val earlier = history.filter { it.conversation && it.id != current?.id }.take(EARLIER_SHOWN)
    val viewing = viewingId?.let { id -> earlier.firstOrNull { it.id == id } }
    val live = current?.takeIf { it.conversation && it.id != hiddenId }
    val shown = viewing ?: live
    val profile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    val working = live?.active == true

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || profile == null) return
        if (live?.status != RunStatus.WAITING || !actions.send(text)) {
            hiddenId = null
            actions.start(text, profile.id, allowSettings, useFallbacks && profiles.size > 1)
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        ChatHeader(
            profiles = profiles,
            profile = profile,
            earlier = earlier,
            canStartNew = shown != null,
            onProfile = { profileId = it },
            onOpenEarlier = { viewingId = it },
            onNewChat = {
                if (viewing == null) {
                    actions.endChat()
                    hiddenId = current?.id
                }
                viewingId = null
            },
            onManageModels = actions.openModels,
        )
        HorizontalDivider()
        if (viewing != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.feature_agent_chat_earlier_banner, dateTime(viewing.startedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewingId = null }) { Text(stringResource(R.string.feature_agent_chat_back)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                profiles.isEmpty() -> NoModels(actions.openModels)
                shown == null -> EmptyChat(onSuggestion = ::send)
                else -> Messages(shown)
            }
        }
        if (viewing == null && live != null && live.active) {
            WorkingBar(live, actions)
        }
        if (viewing == null && profiles.isNotEmpty()) {
            if (live?.open != true) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = allowSettings,
                        onClick = { allowSettings = !allowSettings },
                        label = { Text(stringResource(R.string.feature_agent_chat_allow_settings)) },
                    )
                    if (profiles.size > 1) {
                        FilterChip(
                            selected = useFallbacks,
                            onClick = { useFallbacks = !useFallbacks },
                            label = { Text(stringResource(R.string.feature_agent_chat_fallbacks)) },
                        )
                    }
                }
            }
            // The same bar as Home's quick prompt, so the chat and Home share one layout.
            AgentCommandBar(
                onSubmit = ::send,
                enabled = !working,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ChatHeader(
    profiles: List<AgentProfile>,
    profile: AgentProfile?,
    earlier: List<RunState>,
    canStartNew: Boolean,
    onProfile: (String) -> Unit,
    onOpenEarlier: (String) -> Unit,
    onNewChat: () -> Unit,
    onManageModels: () -> Unit,
) {
    var modelsOpen by remember { mutableStateOf(false) }
    var earlierOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { modelsOpen = true }) {
                Text(profile?.name ?: stringResource(R.string.feature_agent_chat_no_model), maxLines = 1)
            }
            DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                for (p in profiles) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.name)
                                Text(p.model, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onProfile(p.id)
                            modelsOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_agent_chat_manage_models)) },
                    onClick = {
                        modelsOpen = false
                        onManageModels()
                    },
                )
            }
        }
        if (earlier.isNotEmpty()) {
            Box {
                TextButton(onClick = { earlierOpen = true }) { Text(stringResource(R.string.feature_agent_chat_earlier)) }
                DropdownMenu(expanded = earlierOpen, onDismissRequest = { earlierOpen = false }) {
                    for (run in earlier) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(run.goal, maxLines = 1)
                                    Text(dateTime(run.startedAtMillis), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = {
                                onOpenEarlier(run.id)
                                earlierOpen = false
                            },
                        )
                    }
                }
            }
        }
        TextButton(enabled = canStartNew, onClick = onNewChat) { Text(stringResource(R.string.feature_agent_chat_new)) }
    }
}

@Composable
private fun Messages(run: RunState) {
    val items = chatItems(run)
    val listState = rememberLazyListState()
    // Follow the conversation as it grows.
    LaunchedEffect(run.id, items.size, run.steps.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex + 1)
    }
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items) { index, item ->
            when (item) {
                is ChatItem.Owner -> {
                    Bubble(item.text, mine = true)
                }

                is ChatItem.Agent -> {
                    Bubble(item.text, mine = false)
                }

                is ChatItem.Note -> {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is ChatItem.Work -> {
                    WorkRow(item, liveByDefault = run.active && index == items.lastIndex)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.feature_agent_tokens, run.inputTokens, run.outputTokens) +
                        (run.costUsd?.let { "  " + stringResource(R.string.feature_agent_cost, it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(transcript(run))) }) {
                    Text(stringResource(R.string.feature_agent_copy))
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
) {
    val shape =
        RoundedCornerShape(
            topStart = BUBBLE_RADIUS,
            topEnd = BUBBLE_RADIUS,
            bottomStart = if (mine) BUBBLE_RADIUS else BUBBLE_TAIL,
            bottomEnd = if (mine) BUBBLE_TAIL else BUBBLE_RADIUS,
        )
    val content: @Composable () -> Unit = {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        if (mine) {
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH),
                content = content,
            )
        } else {
            // The agent's answers sit on the glass, like Home's cards.
            LiquidGlassBentoCard(
                modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH),
                shape = shape,
                contentPadding = PaddingValues(0.dp),
            ) {
                content()
            }
        }
    }
}

/** The agent's work between two messages, folded to one line unless it is still going. */
@Composable
private fun WorkRow(
    work: ChatItem.Work,
    liveByDefault: Boolean,
) {
    var expanded by rememberSaveable(work.steps.first().index) { mutableStateOf(false) }
    val open = expanded || liveByDefault
    val calls = work.steps.sumOf { it.calls.size }
    val failed = work.steps.sumOf { s -> s.calls.count { it.isError } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.feature_agent_chat_work, calls, failed) +
                    "  " + stringResource(if (open) R.string.feature_agent_hide_steps else R.string.feature_agent_show_steps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                work.steps.takeLast(WORK_STEPS_SHOWN).forEach {
                    Text(stepLine(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun WorkingBar(
    run: RunState,
    actions: ChatActions,
) {
    val paused = run.status == RunStatus.PAUSED
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (!paused) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val last =
                run.steps
                    .lastOrNull { it.calls.isNotEmpty() }
                    ?.calls
                    ?.lastOrNull()
                    ?.tool
            Text(
                if (paused) {
                    stringResource(R.string.feature_agent_status_paused)
                } else {
                    stringResource(R.string.feature_agent_chat_working, last ?: "…")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (paused) {
                Button(onClick = actions.resume) { Text(stringResource(R.string.feature_agent_resume)) }
            } else {
                OutlinedButton(onClick = actions.pause) { Text(stringResource(R.string.feature_agent_pause)) }
            }
            OutlinedButton(onClick = actions.stop) { Text(stringResource(R.string.feature_agent_stop)) }
        }
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    val suggestions =
        listOf(
            stringResource(R.string.feature_agent_chat_suggestion_battery),
            stringResource(R.string.feature_agent_chat_suggestion_dark),
            stringResource(R.string.feature_agent_chat_suggestion_gas),
        )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.feature_agent_chat_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.feature_agent_chat_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        suggestions.forEach { s -> SuggestionChip(onClick = { onSuggestion(s) }, label = { Text(s) }) }
    }
}

@Composable
private fun NoModels(onOpenModels: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.feature_agent_no_profiles), textAlign = TextAlign.Center)
        Button(onClick = onOpenModels) { Text(stringResource(R.string.feature_agent_add_profile)) }
    }
}

private fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private val BUBBLE_RADIUS = 18.dp
private val BUBBLE_TAIL = 4.dp
private val BUBBLE_MAX_WIDTH = 320.dp
private const val EARLIER_SHOWN = 20
private const val WORK_STEPS_SHOWN = 30
