package app.pocketpilot.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * One setup step and Doctor check (design doc section 2): what it is for, whether the phone already
 * has it, and the one action that gets it there.
 */
data class SetupStep(
    val id: String,
    val title: String,
    /** Why PocketPilot asks, and what it will and will not do with it. */
    val reason: String,
    val done: Boolean,
    /** True when the app works without it; everything after Accessibility only adds power. */
    val optional: Boolean = false,
    val actionLabel: String? = null,
    val action: () -> Unit = {},
)

/**
 * The guided setup: one full-screen card per step with its action and a live status that turns
 * green when the phone confirms it. Resumable: the caller keeps [index].
 */
@Composable
fun OnboardingScreen(
    steps: List<SetupStep>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = steps[index.coerceIn(steps.indices)]
    val last = index >= steps.lastIndex
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.feature_onboarding_progress, index + 1, steps.size), style = MaterialTheme.typography.labelLarge)
            Text(step.title, style = MaterialTheme.typography.headlineMedium)
            Text(step.reason, style = MaterialTheme.typography.bodyLarge)
            StatusLine(step)
            step.actionLabel?.takeUnless { step.done }?.let { label ->
                Button(onClick = step.action, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            Spacer(Modifier.padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index >
                    0
                ) {
                    OutlinedButton(onClick = { onIndexChange(index - 1) }) { Text(stringResource(R.string.feature_onboarding_back)) }
                }
                Spacer(Modifier.weight(1f))
                if (last) {
                    Button(onClick = onFinish) { Text(stringResource(R.string.feature_onboarding_finish)) }
                } else {
                    val label = if (step.done) R.string.feature_onboarding_next else R.string.feature_onboarding_skip
                    Button(onClick = { onIndexChange(index + 1) }) { Text(stringResource(label)) }
                }
            }
            if (!last) TextButton(onClick = onFinish) { Text(stringResource(R.string.feature_onboarding_leave)) }
        }
    }
}

/** Every step as a Doctor row: status and, while it is not done, its one-tap fix. */
@Composable
fun SetupChecklist(steps: List<SetupStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium)
                    StatusLine(step)
                }
                step.actionLabel?.takeUnless { step.done }?.let { label -> TextButton(onClick = step.action) { Text(label) } }
            }
        }
    }
}

@Composable
private fun StatusLine(step: SetupStep) {
    val (text, color) =
        when {
            step.done -> stringResource(R.string.feature_onboarding_done) to DONE_GREEN
            step.optional -> stringResource(R.string.feature_onboarding_optional) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> stringResource(R.string.feature_onboarding_not_yet) to MaterialTheme.colorScheme.error
        }
    Text((if (step.done) "✓ " else "○ ") + text, color = color, style = MaterialTheme.typography.bodySmall)
}

private val DONE_GREEN = Color(0xFF2E7D32)
